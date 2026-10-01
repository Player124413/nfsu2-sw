/*
 * Android virtual pad state.
 *
 * The launcher draws the touch controls in Java, but the title still sees a
 * normal Xbox XInput state. Keeping this tiny bridge in the input library
 * means the guest never depends on Android or JNI types and physical SDL
 * controllers can be merged with touch input by xinput_device.c.
 */
#ifndef XBOX_ANDROID_INPUT_H
#define XBOX_ANDROID_INPUT_H

#include <stdint.h>

void android_input_set_state(uint16_t digital, const uint8_t analog[8],
                             const int16_t thumbs[4]);
void android_input_get_state(uint16_t *digital, uint8_t analog[8],
                             int16_t thumbs[4], uint32_t *packet);

#endif
