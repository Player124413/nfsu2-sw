/* gl_api.c -- fill the entry-point table declared in gl_api.h. */
#include "gl_api.h"

#include <stdio.h>
#include <string.h>

#if defined(NFSU2_ANDROID)
/* GLES 3 uses the f-suffixed names for the two depth functions while the
 * renderer's desktop API table uses the GL 3.x spellings. They need wrappers,
 * not just a cast: on ARM64 a double and a float use the same FP register but
 * different bit layouts. */
static void (*s_gl_clear_depth_f)(float);
static void (*s_gl_depth_range_f)(float, float);

static void android_clear_depth(double depth)
{
    if (s_gl_clear_depth_f)
        s_gl_clear_depth_f((float)depth);
}

static void android_depth_range(double near_value, double far_value)
{
    if (s_gl_depth_range_f)
        s_gl_depth_range_f((float)near_value, (float)far_value);
}

static void *getproc_compat(void *(*getproc)(const char *), const char *name)
{
    void *p;
    if (strcmp(name, "glClearDepth") == 0) {
        s_gl_clear_depth_f = (void (*)(float))getproc("glClearDepthf");
        return s_gl_clear_depth_f ? (void *)android_clear_depth : NULL;
    }
    if (strcmp(name, "glDepthRange") == 0) {
        s_gl_depth_range_f = (void (*)(float, float))getproc("glDepthRangef");
        return s_gl_depth_range_f ? (void *)android_depth_range : NULL;
    }
    p = getproc(name);
    return p;
}
#else
static void *getproc_compat(void *(*getproc)(const char *), const char *name)
{
    return getproc(name);
}
#endif

#define NV2A_GL_DEFINE(ret, name, args) PFN_##name p_##name;
NV2A_GL_FUNCS(NV2A_GL_DEFINE)
#undef NV2A_GL_DEFINE

int nv2a_gl_load(void *(*getproc)(const char *name))
{
    int missing = 0;

#define NV2A_GL_RESOLVE(ret, name, args) \
    p_##name = (PFN_##name)getproc_compat(getproc, #name); \
    if (!p_##name) { \
        fprintf(stderr, "  [GL] missing entry point %s\n", #name); \
        missing++; \
    }
    NV2A_GL_FUNCS(NV2A_GL_RESOLVE)
#undef NV2A_GL_RESOLVE
    return missing;
}
