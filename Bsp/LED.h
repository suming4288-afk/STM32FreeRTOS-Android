#ifndef __LED_H
#define __LED_H

void LED_Init(void);
void LED1_ON(void);
void LED1_OFF(void);
void LED1_Turn(void);
void TIM3_PWM_Init(void);
void LED_SetBrightness(uint16_t Compare);


#endif
