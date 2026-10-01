#include <dirent.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>

static const uint64_t s_a = 0x3DCD76DD0506EF98ull;
static const uint64_t s_b = 0xDD0D25178FC8F63Eull;
static const uint64_t s_n[12] = {
    4038656, 32688,
    235780096, 242006016, 248778752, 245807104, 243816448,
    244525056, 170592256, 110309376, 296962048, 396361728,
};

static uint64_t h64(const uint8_t *p, size_t n)
{
    uint64_t h = 0xCBF29CE484222325ull;
    while (n--)
        h = (h ^ *p++) * 0x100000001B3ull;
    return h;
}

static int is_part(const char *n, int *idx)
{
    if (strcasecmp(n, "ZDIR.BIN") == 0) { *idx = 1; return 1; }
    if (strncasecmp(n, "ZZDATA", 6) == 0 && n[6] >= '0' && n[6] <= '9' &&
        strcasecmp(n + 7, ".BIN") == 0) { *idx = 2 + (n[6] - '0'); return 1; }
    return 0;
}

int nfsu2_data_init(const char *game_dir, const void *xbe, size_t xbe_size)
{
    char dir[560], path[1024];
    unsigned seen = 0;
    DIR *d;
    struct dirent *de;
    int ok = 1;

    if (xbe_size != s_n[0] || h64(xbe, xbe_size) != s_a)
        return 0;
    snprintf(dir, sizeof(dir), "%s/NFSUNDER", game_dir);
    d = opendir(dir);
    if (!d)
        return 0;
    while (ok && (de = readdir(d)) != NULL) {
        struct stat st;
        int i;
        if (de->d_name[0] == '.')
            continue;
        if (!is_part(de->d_name, &i) || (seen & (1u << i))) { ok = 0; break; }
        snprintf(path, sizeof(path), "%s/%s", dir, de->d_name);
        if (stat(path, &st) != 0 || !S_ISREG(st.st_mode) ||
            (uint64_t)st.st_size != s_n[i]) { ok = 0; break; }
        if (i == 1) {
            static uint8_t buf[32689];
            FILE *f = fopen(path, "rb");
            size_t n = f ? fread(buf, 1, sizeof(buf), f) : 0;
            if (f)
                fclose(f);
            if (n != s_n[1] || h64(buf, n) != s_b)
                ok = 0;
        }
        seen |= 1u << i;
    }
    closedir(d);
    return ok && seen == 0xFFEu;
}
