#ifndef __HCSR04_H
#define __HCSR04_H

#include "stm32f10x.h"

// 引脚宏定义（可根据实际接线修改）
#define HCSR04_PORT     GPIOA
#define HCSR04_TRIG     GPIO_Pin_3
#define HCSR04_ECHO     GPIO_Pin_4
#define HCSR04_CLK      RCC_APB2Periph_GPIOA

// 函数声明
void HCSR04_Init(void);
float HCSR04_GetValue(void);

#endif
