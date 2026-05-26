#include "Thermistor.h"
#include "math.h"


// 假设热敏电接在PA0引脚
#define THERMISTOR_PIN    GPIO_Pin_0
#define THERMISTOR_GPIO   GPIOA

// 温度计算公式所需的常数，依赖于热敏电阻型号和校准
#define R25 10000   // 25度时的热敏电阻值，假设为10kΩ
#define B_VALUE 3950 // 热敏电阻B常数，视型号而定

// 温度转换函数（这里使用Steinhart-Hart方程的简化版本）
float GetTemperature(uint16_t adcValue)
{
    // 将ADC值转换为电压（假设参考电压3.3V）
    float voltage = (adcValue / 4095.0) * 3.3;

    // 根据电压计算热敏电阻的阻值
    float resistance = (3.3 - voltage) * 10000 / voltage;

    // 计算温度（假设使用B值法则）
    float temperature = 1.0 / (1.0 / 298.15 + (1.0 / B_VALUE) * log(resistance / R25)) - 273.15;

    return temperature;
}

void ADC1_Configuration(void)
{
    GPIO_InitTypeDef GPIO_InitStructure;
    ADC_InitTypeDef ADC_InitStructure;

    // 使能ADC1和GPIOA时钟
    RCC_APB2PeriphClockCmd(RCC_APB2Periph_GPIOA | RCC_APB2Periph_ADC1, ENABLE);

    // 配置PA0为模拟输入
    GPIO_InitStructure.GPIO_Pin = THERMISTOR_PIN;
    GPIO_InitStructure.GPIO_Mode = GPIO_Mode_AIN;
    GPIO_Init(THERMISTOR_GPIO, &GPIO_InitStructure);

    // 配置ADC
    ADC_InitStructure.ADC_Mode = ADC_Mode_Independent;
    ADC_InitStructure.ADC_ScanConvMode = DISABLE;
    ADC_InitStructure.ADC_ContinuousConvMode = ENABLE;
    ADC_InitStructure.ADC_ExternalTrigConv = ADC_ExternalTrigConv_None;
    ADC_InitStructure.ADC_DataAlign = ADC_DataAlign_Right;
    ADC_InitStructure.ADC_NbrOfChannel = 1;
    ADC_Init(ADC1, &ADC_InitStructure);

    // 启动ADC1
    ADC_Cmd(ADC1, ENABLE);

    // 校准ADC
    ADC_ResetCalibration(ADC1);
    while (ADC_GetResetCalibrationStatus(ADC1));
    ADC_StartCalibration(ADC1);
    while (ADC_GetCalibrationStatus(ADC1));
}

uint16_t ADC1_Read(void)
{
    // 开始转换
    ADC_SoftwareStartConvCmd(ADC1, ENABLE);

    // 等待转换完成
    while (!ADC_GetFlagStatus(ADC1, ADC_FLAG_EOC));

    // 获取转换结果
    return ADC_GetConversionValue(ADC1);
}
