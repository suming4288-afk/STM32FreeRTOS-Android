#include "stm32f10x.h"
#include "LightSensor.h"


void ADC1_Init(void)
{
    ADC_InitTypeDef ADC_InitStructure;
    GPIO_InitTypeDef GPIO_InitStructure;

    // 1. 开启时钟
    RCC_APB2PeriphClockCmd(RCC_APB2Periph_GPIOA | RCC_APB2Periph_ADC1, ENABLE);
    RCC_ADCCLKConfig(RCC_PCLK2_Div6); // 72MHz/6 = 12MHz, ADC最高14MHz

    // 2. 配置 PA1 为模拟输入
    GPIO_InitStructure.GPIO_Pin = GPIO_Pin_1;
    GPIO_InitStructure.GPIO_Mode = GPIO_Mode_AIN; // 模拟输入模式
    GPIO_Init(GPIOA, &GPIO_InitStructure);

    // 3. ADC 结构体配置
    ADC_InitStructure.ADC_Mode = ADC_Mode_Independent; // 独立模式
    ADC_InitStructure.ADC_ScanConvMode = DISABLE;       // 单通道，关闭扫描
    ADC_InitStructure.ADC_ContinuousConvMode = ENABLE;  // 连续转换模式
    ADC_InitStructure.ADC_ExternalTrigConv = ADC_ExternalTrigConv_None; // 软件触发
    ADC_InitStructure.ADC_DataAlign = ADC_DataAlign_Right; // 右对齐
    ADC_InitStructure.ADC_NbrOfChannel = 1;
    ADC_Init(ADC1, &ADC_InitStructure);

    // 4. 配置通道采样周期
    ADC_RegularChannelConfig(ADC1, ADC_Channel_1, 1, ADC_SampleTime_55Cycles5);

    // 5. 使能 ADC 并校准
    ADC_Cmd(ADC1, ENABLE);
    ADC_ResetCalibration(ADC1);
    while(ADC_GetResetCalibrationStatus(ADC1));
    ADC_StartCalibration(ADC1);
    while(ADC_GetCalibrationStatus(ADC1));

    // 6. 开启软件转换
    ADC_SoftwareStartConvCmd(ADC1, ENABLE);
}

// 获取 ADC 值的简单封装
uint16_t Get_Light_Value(void)
{
    return ADC_GetConversionValue(ADC1);
}
