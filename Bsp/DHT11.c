#include "DHT11.h"

/* * 将 DHT11 连接的 GPIO 引脚配置为输入模式（带上拉）
 * 用于读取 DHT11 发回的响应信号和数据
 */
static void DHT11_GPIO_Input(void)
{
    GPIO_InitTypeDef GPIO_InitStruct = {
        .GPIO_Pin = DHT11_GPIO_PIN,        // 指定引脚号
        .GPIO_Mode = GPIO_Mode_IPU,        // 设置为上拉输入模式 (Input Pull-Up)
        .GPIO_Speed = GPIO_Speed_50MHz     // 设置 IO 口速度为 50MHz
    };
    GPIO_Init(DHT11_GPIO_PORT, &GPIO_InitStruct); // 根据上述配置初始化 GPIO 端口
}

/* * 将 DHT11 连接的 GPIO 引脚配置为输出模式（推挽输出）
 * 用于主机向 DHT11 发送起始信号（拉低总线）
 */
static void DHT11_GPIO_Output(void)
{
    GPIO_InitTypeDef GPIO_InitStruct = {
        .GPIO_Pin = DHT11_GPIO_PIN,        // 指定引脚号
        .GPIO_Mode = GPIO_Mode_Out_PP,     // 设置为推挽输出模式 (Output Push-Pull)
        .GPIO_Speed = GPIO_Speed_50MHz     // 设置 IO 口速度为 50MHz
    };
    GPIO_Init(DHT11_GPIO_PORT, &GPIO_InitStruct); // 根据上述配置初始化 GPIO 端口
}

/* * 微秒级延迟函数
 * 基于 72MHz 系统主频进行循环计数模拟延迟
 */
static void DHT11_Delay(uint16_t us)
{
    // 在 72MHz 下，经过校准，us * 9 大约对应所需的微秒数
    volatile uint32_t cycles = us * 9;  
    while(cycles--); // 递减直到为 0，消耗 CPU 时间
}

/* * DHT11 初始化函数
 */
void DHT11_Init(DHT11_HandleTypeDef *hsensor) 
{
    // 开启 DHT11 引脚所属的 GPIO 端口时钟 (通常在 APB2 总线上)
    RCC_APB2PeriphClockCmd(DHT11_RCC_APB, ENABLE);
    // 创建一个互斥信号量，防止多个任务同时操作同一个传感器造成时序混乱
    hsensor->mutex = xSemaphoreCreateMutex();
    // 断言检查：确保信号量创建成功，否则进入调试停顿
    configASSERT(hsensor->mutex != NULL);
    // 初始化上一次读取的时间戳为 0
    hsensor->last_read_tick = 0;
}

/* * 读取 DHT11 数据的主函数
 * 返回值：pdTRUE 表示读取成功并校验通过；pdFALSE 表示失败
 */
BaseType_t DHT11_Read(DHT11_HandleTypeDef *hsensor) 
{
    uint8_t buffer[5] = {0}; // 定义 5 字节缓冲区，存放 40 位原始数据 (湿整, 湿小, 温整, 温小, 校验)
    uint32_t timeout;        // 定义超时计数器
    
    // 获取互斥锁，等待时间最长为 100ms
    if(xSemaphoreTake(hsensor->mutex, pdMS_TO_TICKS(100)) != pdPASS) {
        return pdFALSE; // 如果拿不到锁，直接返回失败
    }
    
    // DHT11 两次读取之间需要一定的间隔（通常为 1-2 秒），防止传感器发热或无法响应
    if((xTaskGetTickCount() - hsensor->last_read_tick) < DHT11_READ_INTERVAL) {
        // 如果间隔太短，让任务进入延时状态，直到满足最小读取间隔
        vTaskDelay(DHT11_READ_INTERVAL - (xTaskGetTickCount() - hsensor->last_read_tick));
    }

    // 进入临界区，禁止 FreeRTOS 任务切换，确保接下来的单总线时序不会被中断
    taskENTER_CRITICAL();
    
    /* --- 发送起始信号 --- */
    DHT11_GPIO_Output();                      // 切换为输出模式
    GPIO_ResetBits(DHT11_GPIO_PORT, DHT11_GPIO_PIN); // 拉低总线
    DHT11_Delay(18000);                       // 持续拉低 18ms，保证 DHT11 能检测到起始信号
    
    GPIO_SetBits(DHT11_GPIO_PORT, DHT11_GPIO_PIN);   // 释放总线（拉高）
    DHT11_Delay(30);                          // 主机拉高等待 20~40us，准备接收响应
    
    /* --- 切换输入模式 --- */
    DHT11_GPIO_Input();                       // 切换为输入模式，准备读取传感器信号
    DHT11_Delay(10);                          // 短暂延迟等待 IO 状态稳定
    
    /* --- 检测 DHT11 响应 --- */
    timeout = 0;
    // 等待总线被传感器拉低（DHT11 会拉低 80us 作为响应信号）
    while(GPIO_ReadInputDataBit(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == Bit_SET) {
        if(timeout++ > 100) {                 // 超时处理：如果总线一直不被拉低
            taskEXIT_CRITICAL();              // 退出临界区
            xSemaphoreGive(hsensor->mutex);   // 释放互斥锁
            return pdFALSE;                   // 返回读取失败
        }
        DHT11_Delay(1);
    }
    
    timeout = 0;
    // 等待 DHT11 重新拉高总线（拉高 80us，准备开始传送数据）
    while(GPIO_ReadInputDataBit(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == Bit_RESET) {
        if(timeout++ > 200) {                 // 超时处理
            taskEXIT_CRITICAL();
            xSemaphoreGive(hsensor->mutex);
            return pdFALSE;
        }
        DHT11_Delay(1);
    }
    
    timeout = 0;
    // 等待 DHT11 再次拉低（正式开始数据位传输的标志）
    while(GPIO_ReadInputDataBit(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == Bit_SET) {
        if(timeout++ > 200) {                 // 超时处理
            taskEXIT_CRITICAL();
            xSemaphoreGive(hsensor->mutex);
            return pdFALSE;
        }
        DHT11_Delay(1);
    }
    
    /* --- 读取 40 位数据 --- */
    for(uint8_t i=0; i<40; i++) {
        // 每位数据开始前，总线都会被拉低 50us
        while(GPIO_ReadInputDataBit(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == Bit_RESET);
        
        // 当总线变高后，根据高电平持续的时间长短判断 0 还是 1
        DHT11_Delay(40);  // 等待 40us，若 40us 后仍为高，则说明持续时间大于 28us，判定为二进制 '1'
        
        if(GPIO_ReadInputDataBit(DHT11_GPIO_PORT, DHT11_GPIO_PIN)) {
            // 如果判定为 '1'，则将对应的 buffer 字节位设为 1
            buffer[i/8] |= (1 << (7 - (i%8)));
            
            timeout = 0;
            // 等待该位数据结束（总线变低），防止进入下一循环干扰时序
            while(GPIO_ReadInputDataBit(DHT11_GPIO_PORT, DHT11_GPIO_PIN) == Bit_SET) {
                if(timeout++ > 100) break;
                DHT11_Delay(1);
            }
        }
    }
    
    // 退出临界区，允许 FreeRTOS 恢复调度
    taskEXIT_CRITICAL();
    // 释放互斥锁
    xSemaphoreGive(hsensor->mutex);
    
    /* --- 数据校验 --- */
    // 前四个字节（温湿度整小数部分）之和应等于第五个字节（校验位）
    if(buffer[0] + buffer[1] + buffer[2] + buffer[3] == buffer[4]) {
        hsensor->humidity = buffer[0];         // 将湿度整数部分保存到句柄
        hsensor->temperature = buffer[2];      // 将温度整数部分保存到句柄
        hsensor->last_read_tick = xTaskGetTickCount(); // 记录本次读取成功的时间戳
        return pdTRUE;                         // 校验通过，返回成功
    }
    
    return pdFALSE; // 校验失败
}
