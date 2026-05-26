#ifndef __THERMISTOR_H
#define __THERMISTOR_H

#include "stm32f10x.h"

float GetTemperature(uint16_t adcValue);

void ADC1_Configuration(void);
uint16_t ADC1_Read(void);

#endif
