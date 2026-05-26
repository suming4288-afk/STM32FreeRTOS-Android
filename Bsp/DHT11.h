/* dht11.h */
#ifndef __DHT11_H        // 如果没有定义 __DHT11_H (防止头文件被重复包含导致编译错误)
#define __DHT11_H        // 定义 __DHT11_H

#include "stm32f10x.h"   // 包含 STM32 标准外设库，用于控制 GPIO 等寄存器
#include "FreeRTOS.h"    // 包含 FreeRTOS 核心配置
#include "task.h"        // 包含 FreeRTOS 任务管理 API (用于获取系统 Tick)
#include "semphr.h"      // 包含 FreeRTOS 信号量 API (用于互斥锁)

/* *********************************************************************************************************
* 数据结构定义
*********************************************************************************************************
*/
typedef struct {
    uint8_t humidity;          // 成员：存储读取到的湿度整数部分
    uint8_t temperature;       // 成员：存储读取到的温度整数部分
    TickType_t last_read_tick; // 成员：记录上一次成功读取时系统的 Tick 值 (用于控制读取频率)
    SemaphoreHandle_t mutex;   // 成员：FreeRTOS 互斥锁句柄 (保证多任务环境下对单总线的独占访问)
} DHT11_HandleTypeDef;         // 结构体类型别名：DHT11 句柄，用于管理传感器状态

/* *********************************************************************************************************
* 硬件接口宏定义 (根据实际接线修改)
*********************************************************************************************************
*/
#define DHT11_GPIO_PORT        GPIOA                // 定义 DHT11 连接的 GPIO 分组为 GPIOA
#define DHT11_GPIO_PIN         GPIO_Pin_7           // 定义 DHT11 连接的引脚为 Pin 6
#define DHT11_RCC_APB          RCC_APB2Periph_GPIOA // 定义需要开启的 GPIOA 时钟外设
#define DHT11_READ_INTERVAL    pdMS_TO_TICKS(1500)  // 定义两次读取之间的最小间隔为 1500ms (DHT11 采样频率较低)

/* *********************************************************************************************************
* 函数声明
*********************************************************************************************************
*/

/**
 * @brief  初始化 DHT11 硬件和相关的操作系统资源
 * @param  hsensor: DHT11 句柄指针
 */
void DHT11_Init(DHT11_HandleTypeDef *hsensor);

/**
 * @brief  从 DHT11 读取温湿度数据
 * @param  hsensor: DHT11 句柄指针 (读取成功后数据存放在此结构体中)
 * @return BaseType_t: 返回 pdTRUE 表示读取并校验成功，pdFALSE 表示失败
 */
BaseType_t DHT11_Read(DHT11_HandleTypeDef *hsensor);

#endif /* __DHT11_H */ // 结束条件编译
