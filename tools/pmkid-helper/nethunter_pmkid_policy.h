/* NetHunter PMKID safety policy, MIT licensed; see upstream/license.txt. */
#ifndef NETHUNTER_PMKID_POLICY_H
#define NETHUNTER_PMKID_POLICY_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>

#define NETHUNTER_PMKID_MARKER "nethunter-pmkid-1"

typedef struct {
    uint8_t target[6];
    uint8_t client[6];
    bool configured;
    bool interface_ready;
    bool bpf_attached;
} nh_pmkid_policy;

static inline unsigned nh_le16(const uint8_t *p)
{ return (unsigned)p[0] | ((unsigned)p[1] << 8); }

static inline unsigned nh_be16(const uint8_t *p)
{ return ((unsigned)p[0] << 8) | (unsigned)p[1]; }

static inline int nh_hex_digit(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Exact compact hex or colon-separated MAC; reject trailing/partial input. */
static inline bool nh_parse_mac(const char *s, uint8_t out[6], bool require_laa)
{
    uint8_t parsed[6];
    size_t n;
    bool nonzero = false;
    if (!s) return false;
    n = strlen(s);
    if (n != 12 && n != 17) return false;
    for (size_t i = 0; i < 6; ++i) {
        size_t at = n == 17 ? i * 3 : i * 2;
        int a = nh_hex_digit(s[at]), b = nh_hex_digit(s[at + 1]);
        if (a < 0 || b < 0 || (n == 17 && i < 5 && s[at + 2] != ':')) return false;
        parsed[i] = (uint8_t)((a << 4) | b);
        nonzero |= parsed[i] != 0;
    }
    if (!nonzero || (parsed[0] & 1) || (require_laa && !(parsed[0] & 2))) return false;
    memcpy(out, parsed, 6);
    return true;
}

static inline bool nh_parse_frequency(const char *s, unsigned *frequency)
{
    unsigned n = 0;
    if (!s || !*s) return false;
    for (; *s; ++s) {
        if (*s < '0' || *s > '9') return false;
        n = n * 10 + (unsigned)(*s - '0');
        if (n > 65535) return false;
    }
    if (!n) return false;
    *frequency = n;
    return true;
}

/* Validate the bytes sent to the driver, independent of upstream handlers. */
static inline bool nh_pmkid_tx_allowed(const nh_pmkid_policy *p,
                                      const void *packet, size_t length)
{
    const uint8_t *rt = packet, *f;
    size_t rlen, flen;
    if (!p || !p->configured || !packet || length < 8 || rt[0] != 0) return false;
    rlen = nh_le16(rt + 2);
    if (rlen < 8 || rlen > length || length - rlen < 10) return false;
    f = rt + rlen;
    flen = length - rlen;
    if (memcmp(f + 4, p->target, 6) != 0) return false;
    /* ACK has only a receiver address; it has no transmitter address field. */
    if (f[0] == 0xd4) return f[1] == 0 && flen == 10;
    if (flen < 24 || memcmp(f + 10, p->client, 6) || memcmp(f + 16, p->target, 6)) return false;
    if (f[0] == 0xb0) {
        /* Open-system authentication request: transaction 1, success status. */
        return f[1] == 0 && flen == 30 && nh_le16(f + 24) == 0 &&
               nh_le16(f + 26) == 1 && nh_le16(f + 28) == 0;
    }
    if (f[0] == 0x00) return f[1] == 0 && flen >= 28; /* association request */
    if (f[0] == 0x48) return f[1] == 1 && (flen == 24 || flen == 26); /* STA null */
    return false;
}

/* Only the selected AP can reach the upstream state machine. Other clients,
 * EAPOL-Start, action/control/null frames and rogue AP requests are excluded. */
static inline bool nh_pmkid_rx_allowed(const nh_pmkid_policy *p,
                                      const void *frame, size_t length)
{
    static const uint8_t broadcast[6] = {255,255,255,255,255,255};
    static const uint8_t llc[8] = {0xaa,0xaa,0x03,0,0,0,0x88,0x8e};
    const uint8_t *f = frame, *eap, *key;
    size_t header, available;
    unsigned eaplen;
    if (!p || !p->configured || !frame || length < 24 ||
        memcmp(f + 10, p->target, 6) || memcmp(f + 16, p->target, 6)) return false;
    if (f[0] == 0x80 || f[0] == 0x50) {
        return (f[1] & 0xc3) == 0 && length >= 38 &&
               (!memcmp(f + 4, broadcast, 6) || !memcmp(f + 4, p->client, 6));
    }
    if (memcmp(f + 4, p->client, 6)) return false;
    if (f[0] == 0xb0) {
        return (f[1] & 0xc3) == 0 && length >= 30 && nh_le16(f + 24) == 0 &&
               nh_le16(f + 26) == 2 && nh_le16(f + 28) == 0;
    }
    if (f[0] == 0x10) return (f[1] & 0xc3) == 0 && length >= 30 && nh_le16(f + 26) == 0;
    if ((f[0] != 0x08 && f[0] != 0x88) || (f[1] & 0xc3) != 2) return false;
    header = f[0] == 0x88 ? 26 : 24;
    if (length < header + 8 + 4 + 95 || memcmp(f + header, llc, 8)) return false;
    eap = f + header + 8;
    available = length - header - 8 - 4;
    eaplen = nh_be16(eap + 2);
    if (eap[0] < 1 || eap[0] > 3 || eap[1] != 3 || eaplen < 95 || eaplen > available) return false;
    key = eap + 4;
    if (nh_be16(key + 93) > eaplen - 95) return false;
    /* Receive only M1: ACK set, INSTALL clear, a nonzero key descriptor version. */
    return (nh_be16(key + 1) & 0x0087) > 0x0080 && !(nh_be16(key + 1) & 0x0040);
}

typedef int (*nh_attach_fn)(int, const void *);
typedef ssize_t (*nh_write_fn)(int, const void *, size_t);

static inline bool nh_pmkid_attach_filter(nh_pmkid_policy *p, int fd,
                                         const void *filter, nh_attach_fn attach)
{
    p->bpf_attached = false;
    if (!p->configured || !p->interface_ready || !filter || !attach || attach(fd, filter) != 0) return false;
    p->bpf_attached = true;
    return true;
}

static inline ssize_t nh_pmkid_write(const nh_pmkid_policy *p, int fd,
                                    const void *packet, size_t length, nh_write_fn writer)
{
    if (!p || !p->interface_ready || !p->bpf_attached ||
        !writer || !nh_pmkid_tx_allowed(p, packet, length)) {
        errno = EPERM;
        return -1;
    }
    return writer(fd, packet, length);
}
#endif
