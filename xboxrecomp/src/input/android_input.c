/* Android touch pad state shared by the UI thread and the guest input poll. */
#if defined(__ANDROID__)
#include "android_input.h"

#include <pthread.h>
#include <string.h>

static pthread_mutex_t s_lock = PTHREAD_MUTEX_INITIALIZER;
static uint16_t s_digital;
static uint8_t s_analog[8];
static int16_t s_thumbs[4];
static uint32_t s_packet;

void android_input_set_state(uint16_t digital, const uint8_t analog[8],
                             const int16_t thumbs[4])
{
    pthread_mutex_lock(&s_lock);
    s_digital = digital;
    memcpy(s_analog, analog, sizeof s_analog);
    memcpy(s_thumbs, thumbs, sizeof s_thumbs);
    /* XInput clients use packet changes for cheap edge detection. Increment
     * even when the user sends the same state: two quick taps can otherwise
     * collapse if Android coalesces their motion events. */
    s_packet++;
    pthread_mutex_unlock(&s_lock);
}

void android_input_get_state(uint16_t *digital, uint8_t analog[8],
                             int16_t thumbs[4], uint32_t *packet)
{
    pthread_mutex_lock(&s_lock);
    *digital = s_digital;
    memcpy(analog, s_analog, sizeof s_analog);
    memcpy(thumbs, s_thumbs, sizeof s_thumbs);
    *packet = s_packet;
    pthread_mutex_unlock(&s_lock);
}
#endif
