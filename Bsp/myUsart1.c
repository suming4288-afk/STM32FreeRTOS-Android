#include "myUsart1.h"
#include "FreeRTOS.h"
#include "queue.h"

#define RX_BUF_SIZE 64

// 引入在 main.c 中定义的指令队列句柄
extern QueueHandle_t xCmdQueue; 
//uint8_t RxData = 0xFF; // 默认无效值
extern uint16_t g_UsartRxLen; // 记录当前接收到的字符长度

extern char g_UsartRxBuf[RX_BUF_SIZE];

void USART1_Init(uint32_t bound) {
GPIO_InitTypeDef GPIO_Initstructure;
NVIC_InitTypeDef NVIC_InitStruct;
USART_InitTypeDef USART1_InitStructure;

RCC_APB2PeriphClockCmd(RCC_APB2Periph_GPIOA, ENABLE);
RCC_APB2PeriphClockCmd(RCC_APB2Periph_USART1, ENABLE); 

GPIO_Initstructure.GPIO_Mode = GPIO_Mode_AF_PP;
GPIO_Initstructure.GPIO_Pin = GPIO_Pin_9;
GPIO_Initstructure.GPIO_Speed = GPIO_Speed_50MHz;
GPIO_Init(GPIOA, &GPIO_Initstructure);

GPIO_Initstructure.GPIO_Mode = GPIO_Mode_IN_FLOATING;
GPIO_Initstructure.GPIO_Pin = GPIO_Pin_10;
GPIO_Init(GPIOA, &GPIO_Initstructure);

USART1_InitStructure.USART_BaudRate = bound;
USART1_InitStructure.USART_HardwareFlowControl = USART_HardwareFlowControl_None;
USART1_InitStructure.USART_Mode = USART_Mode_Rx | USART_Mode_Tx;
USART1_InitStructure.USART_Parity = USART_Parity_No;
USART1_InitStructure.USART_StopBits = USART_StopBits_1;
USART1_InitStructure.USART_WordLength = USART_WordLength_8b;
USART_Init(USART1, &USART1_InitStructure);

NVIC_InitStruct.NVIC_IRQChannel = USART1_IRQn;
NVIC_InitStruct.NVIC_IRQChannelCmd = ENABLE;
NVIC_InitStruct.NVIC_IRQChannelPreemptionPriority = 10; // 必须低于5
NVIC_InitStruct.NVIC_IRQChannelSubPriority = 0;
NVIC_Init(&NVIC_InitStruct);

USART_ITConfig(USART1, USART_IT_RXNE, ENABLE);
USART_Cmd(USART1, ENABLE); 
}


void USART_SendString(USART_TypeDef* USARTx, char *str) {
while (*str != '\0') {
	USART_SendData(USARTx, (uint16_t)*str);
	while (USART_GetFlagStatus(USARTx, USART_FLAG_TXE) == RESET);
	str++;
}
}

/**
* @brief 串口 1 中断服务函数
*/
void USART1_IRQHandler(void) 
{
    uint8_t RxData;
    BaseType_t xHigherPriorityTaskWoken = pdFALSE; 
    uint8_t cmd_trigger = 0xFE; // 字符串结束触发信号

    if (USART_GetITStatus(USART1, USART_IT_RXNE) != RESET) 
    {
        RxData = USART_ReceiveData(USART1);

        // --- 新增：识别单字节 LED 控制指令 ---
        if (RxData == 0x01 || RxData == 0x00) 
        {
            // 如果是 LED 指令，直接把 RxData (0x01或0x00) 发送给队列，不进缓冲区
            if (xCmdQueue != NULL) {
                xQueueSendFromISR(xCmdQueue, &RxData, &xHigherPriorityTaskWoken);
            }
        } 
        // --- 原有的字符串接收逻辑 ---
        else if (g_UsartRxLen < (RX_BUF_SIZE - 1)) 
        {
            if (RxData == '\n') 
            {
                g_UsartRxBuf[g_UsartRxLen] = '\0'; 
                g_UsartRxLen = 0;                  
                
                // 发送 0xFE，告诉任务去解析缓冲区里的字符串
                if (xCmdQueue != NULL) {
                    xQueueSendFromISR(xCmdQueue, &cmd_trigger, &xHigherPriorityTaskWoken);
                }
            } 
            else if (RxData != '\r') 
            {
                g_UsartRxBuf[g_UsartRxLen++] = RxData;
            }
        } 
        else 
        {
            g_UsartRxLen = 0; 
        }

        USART_ClearITPendingBit(USART1, USART_IT_RXNE);
    }
    portYIELD_FROM_ISR(xHigherPriorityTaskWoken);
}
