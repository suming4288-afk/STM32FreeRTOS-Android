#include "HCSR04.h"

/**
  * @brief  初始化超声波相关的GPIO和TIM2
  */
void HCSR04_Init(void)
{
    GPIO_InitTypeDef GPIO_InitStructure;
    TIM_TimeBaseInitTypeDef TIM_TimeBaseStructure;

    // 1. 开启 GPIO 和 TIM2 时钟
    RCC_APB2PeriphClockCmd(HCSR04_CLK, ENABLE);
    RCC_APB1PeriphClockCmd(RCC_APB1Periph_TIM2, ENABLE);

    // 2. 配置 Trig 为推挽输出
    GPIO_InitStructure.GPIO_Pin = HCSR04_TRIG;
    GPIO_InitStructure.GPIO_Speed = GPIO_Speed_50MHz;
    GPIO_InitStructure.GPIO_Mode = GPIO_Mode_Out_PP;
    GPIO_Init(HCSR04_PORT, &GPIO_InitStructure);

    // 3. 配置 Echo 为浮空输入
    GPIO_InitStructure.GPIO_Pin = HCSR04_ECHO;
    GPIO_InitStructure.GPIO_Mode = GPIO_Mode_IN_FLOATING;
    GPIO_Init(HCSR04_PORT, &GPIO_InitStructure);

    // 4. 配置 TIM2 定时器：1us 计数一次
    TIM_InternalClockConfig(TIM2);
    TIM_TimeBaseStructure.TIM_Period = 65535;            // 自动重装载值最大
    TIM_TimeBaseStructure.TIM_Prescaler = 72 - 1;        // 72MHz / 72 = 1MHz (1us)
    TIM_TimeBaseStructure.TIM_ClockDivision = TIM_CKD_DIV1;
    TIM_TimeBaseStructure.TIM_CounterMode = TIM_CounterMode_Up;
    TIM_TimeBaseInit(TIM2, &TIM_TimeBaseStructure);

    TIM_Cmd(TIM2, DISABLE); // 默认关闭，测距时再开启
    GPIO_ResetBits(HCSR04_PORT, HCSR04_TRIG); // 初始拉低 Trig
}

/**
  * @brief  微秒级延迟函数（用于Trig触发）
  */
static void HCSR04_DelayUs(uint16_t us)
{
    TIM_SetCounter(TIM2, 0);
    TIM_Cmd(TIM2, ENABLE);
    while(TIM_GetCounter(TIM2) < us);
    TIM_Cmd(TIM2, DISABLE);
}

/**
  * @brief  发起一次测距并返回物理距离 (单位: cm)
  */
float HCSR04_GetValue(void)
{
    uint32_t time_us = 0;
    float distance = 0;

    // 1. 发送 10us 以上的高电平触发信号
    GPIO_SetBits(HCSR04_PORT, HCSR04_TRIG);
    HCSR04_DelayUs(15);
    GPIO_ResetBits(HCSR04_PORT, HCSR04_TRIG);

    // 2. 等待 Echo 引脚变高 (开始计时)
    // 增加超时判断，防止硬件断路导致程序死锁
    uint16_t timeout = 0;
    while(GPIO_ReadInputDataBit(HCSR04_PORT, HCSR04_ECHO) == 0)
    {
        timeout++;
        if(timeout > 20000) return 0; // 超时退出
    }

    // 3. 开始记录时间
    TIM_SetCounter(TIM2, 0); // 计数器清零
    TIM_Cmd(TIM2, ENABLE);   // 启动定时器

    // 4. 等待 Echo 引脚变低 (结束计时)
    while(GPIO_ReadInputDataBit(HCSR04_PORT, HCSR04_ECHO) == 1)
    {
        if(TIM_GetCounter(TIM2) > 60000) break; // 距离过远超时
    }
    
    TIM_Cmd(TIM2, DISABLE);  // 关闭定时器
    time_us = TIM_GetCounter(TIM2); // 获取总微秒数

    // 5. 计算距离
    // 公式：$Distance = \frac{Time \times 0.0343}{2}$
    distance = (float)time_us * 0.01715f;

    return distance;
}
