#ifndef __myUsart1_H
#define __myUsart1_H	 
#include "stm32f10x.h"                  // Device header


extern u8 RxData;

void USART1_Init(uint32_t bound);	
void USART_SendString(USART_TypeDef* USARTx, char *str);

#endif
