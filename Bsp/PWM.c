#include "stm32f10x.h"
#include "PWM.h"
void PWM_Init(void)
{
    /* 1. 开启 TIM1 和 GPIOA 时钟 */
    RCC_APB2PeriphClockCmd(RCC_APB2Periph_TIM1, ENABLE);
    RCC_APB2PeriphClockCmd(RCC_APB2Periph_GPIOA, ENABLE);
    
    /* 2. 配置 PA11 为复用推挽输出 (TIM1_CH4) */
    GPIO_InitTypeDef GPIO_InitStructure;
    GPIO_InitStructure.GPIO_Mode = GPIO_Mode_AF_PP;
    GPIO_InitStructure.GPIO_Pin = GPIO_Pin_11; 
    GPIO_InitStructure.GPIO_Speed = GPIO_Speed_50MHz;
    GPIO_Init(GPIOA, &GPIO_InitStructure);
    
    /* 3. 配置 TIM1 时基单元 (20kHz PWM) */
    TIM_TimeBaseInitTypeDef TIM_TimeBaseInitStructure;
    TIM_TimeBaseInitStructure.TIM_ClockDivision = TIM_CKD_DIV1;
    TIM_TimeBaseInitStructure.TIM_CounterMode = TIM_CounterMode_Up;
    TIM_TimeBaseInitStructure.TIM_Period = 100 - 1;   // ARR
    TIM_TimeBaseInitStructure.TIM_Prescaler = 36 - 1; // PSC
    TIM_TimeBaseInitStructure.TIM_RepetitionCounter = 0;
    TIM_TimeBaseInit(TIM1, &TIM_TimeBaseInitStructure);
    
    /* 4. 配置 TIM1_CH4 输出比较 */ 
    TIM_OCInitTypeDef TIM_OCInitStructure;
    TIM_OCStructInit(&TIM_OCInitStructure);
    TIM_OCInitStructure.TIM_OCMode = TIM_OCMode_PWM1;
    TIM_OCInitStructure.TIM_OCPolarity = TIM_OCPolarity_High;
    TIM_OCInitStructure.TIM_OutputState = TIM_OutputState_Enable;
    TIM_OCInitStructure.TIM_Pulse = 20;                // 初始 CCR
    TIM_OC4Init(TIM1, &TIM_OCInitStructure);          // 使用通道 4
    
    /* 5. 特别注意：TIM1 是高级定时器，必须使能主输出才能工作 */
    TIM_CtrlPWMOutputs(TIM1, ENABLE);
    
    /* 6. 使能定时器 */
    TIM_Cmd(TIM1, ENABLE);
}

void PWM_SetCompare4(uint16_t Compare)
{
    TIM_SetCompare4(TIM1, Compare); // 修改 CCR4
}
