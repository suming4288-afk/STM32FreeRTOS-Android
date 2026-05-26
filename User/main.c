/*
*********************************************************************************************************
* 包含头文件
*********************************************************************************************************
*/
#include "stm32f10x.h"                  // STM32标准外设库：定义寄存器及底层API
#include "FreeRTOS.h"                   // FreeRTOS内核：基础配置与数据类型
#include "task.h"                       // FreeRTOS任务：创建、删除、延时函数
#include "queue.h"                      // FreeRTOS队列：任务间通信核心组件
#include "semphr.h"                     // FreeRTOS信号量：同步与互斥机制

// 硬件驱动层
#include "OLED.h"                       // OLED显示屏驱动：支持字符、数字显示
#include "DHT11.h"                      // DHT11传感器驱动：温湿度单总线读取
#include "myUsart1.h"                   // 串口1驱动：底层寄存器初始化及发送函数
#include "LED.h"                        // LED驱动：简单的GPIO高低电平控制
#include "LightSensor.h"                // 光敏驱动：ADC采样及TIM1 PWM亮度控制
#include "HCSR04.h"
#include <stdio.h>                      // 标准C库：提供sprintf用于字符串格式化
#include "Buzzer.h"
#include <string.h>  // 添加此行来解决 strlen 的隐式声明警告
#include <stdlib.h>  // 为 atoi 函数提供声明
#include "Motor.h"
/*
*********************************************************************************************************
* 宏定义与数据结构
*********************************************************************************************************
*/
// 任务优先级定义 (数值越大优先级越高)
#define START_TASK_PRIO         1       // 起始任务优先级（负责资源分配）
#define CMD_CENTER_PRIO         5       // 指令中心优先级（响应App控制，需最高实时性）
#define DHT11_TASK_PRIO         4       // 温湿度采集优先级
#define LIGHT_TASK_PRIO         4       // 光敏采集优先级
#define ULTRASONIC_TASK         4       // 距离采集优先级
#define ROUTER_TASK_PRIO        3       // 数据路由任务优先级（中转站）
#define BUSINESS_TASK_PRIO      3       // 业务逻辑任务优先级（自动控制逻辑）
#define EXEC_TASK_PRIO          2       // 执行层优先级（OLED刷新和蓝牙发送）

// 任务堆栈大小 (单位：字，1字 = 4字节)
#define STK_SIZE_NORMAL         128     // 普通任务分配512字节空间
#define STK_SIZE_LARGE          512     // 涉及字符串处理的任务分配2048字节空间


#define RX_BUF_SIZE 64

// 消息来源 ID 枚举 (用于路由分拣)
typedef enum {
	MSG_ID_DHT11,                       // 标识符：数据来自温湿度传感器
	MSG_ID_LIGHT,                       // 标识符：数据来自光敏电阻
	MSG_ID_DISTANCE,                       // 标识符：数据来自超声波HC-SR04模块
	MSG_ID_SYSTEM_ERR                   // 标识符：系统错误告警
} MessageID_t;

// 统一消息传输结构体
typedef struct {
	MessageID_t id;                     // 消息 ID：告诉接收方这是什么数据
	uint8_t data[4];                    // 数据负载：data[0]主数据，data[1]辅助数据
} SystemMsg_t;

typedef struct {
	uint8_t temp_threshold;    // 温度阈值
	uint8_t humidity_threshold;  // 湿度阈值
	uint8_t distance_threshold;  // 距离阈值
	uint8_t light_threshold;     // 光照阈值
} Thresholds_t;

Thresholds_t current_thresholds = {
	.temp_threshold = 40,      // 默认温度阈值 30°C
	.humidity_threshold = 80,  // 默认湿度阈值 80%
	.distance_threshold = 0,  // 默认距离阈值 10cm
	.light_threshold = 20     // 默认光照阈值 10%
};
/*
*********************************************************************************************************
* 全局函数实现
*********************************************************************************************************
*/
void update_thresholds(uint8_t temp, uint8_t humidity, uint8_t distance, uint8_t light)
{
	current_thresholds.temp_threshold = temp;
	current_thresholds.humidity_threshold = humidity;
	current_thresholds.distance_threshold = distance;
	current_thresholds.light_threshold = light;
}

/*
*********************************************************************************************************
* 全局句柄定义
*********************************************************************************************************
*/
QueueHandle_t xSystemDataQueue;         // 全局总线队列：所有采集数据汇集于此
QueueHandle_t xBusinessQueue;           // 业务控制专用队列（统一处理算法和报警）       // 业务专用队列：仅存放光照数据用于调光
QueueHandle_t xOLEDQueue;               // 显示专用队列：存放需要上屏的数据
QueueHandle_t xBT_TxQueue;              // 蓝牙专用队列：存放需要发往App的数据
QueueHandle_t xCmdQueue;                // 指令专用队列：存放App发来的控制命令

TaskHandle_t StartTask_Handler;         // 起始任务句柄
DHT11_HandleTypeDef hdht11;             // DHT11驱动配置句柄


/*
*********************************************************************************************************
* 任务函数声明
*********************************************************************************************************
*/
void SafeQueueSend(QueueHandle_t xQueue, SystemMsg_t* msg); // 安全发送函数声明
void start_task(void *pvParameters);                        // 创建资源的任务
void dht11_task(void *pvParameters);                        // 采集温湿度
void light_task(void *pvParameters);                        // 采集光强
void ultrasonic_task(void *pvParameters);					// 采集距离
void data_router_task(void *pvParameters);                  // 数据路由中转
void business_control_task(void *pvParameters);             // 智能算法控制
void oled_display_task(void *pvParameters);                 // OLED终端显示
void bluetooth_tx_task(void *pvParameters);                 // 蓝牙协议封包发送
void command_center_task(void *pvParameters);               // 解析App下行指令
/*
*********************************************************************************************************
* 主函数
*********************************************************************************************************
*/
/* 增加全局变量 */

char g_UsartRxBuf[RX_BUF_SIZE];
uint16_t g_UsartRxLen = 0; // 记录当前接收到的字符长度

int main(void)
{
	NVIC_PriorityGroupConfig(NVIC_PriorityGroup_4); // 设置中断优先级分组为4（RTOS标准要求）

	// 硬件底层逐一初始化
	OLED_Init();                                    // 初始化OLED引脚及寄存器
	OLED_Clear();                                   // 清空屏幕显存，防止上电花屏
	LED_Init();                                     // 初始化LED相关的GPIO口
	ADC1_Init();                                    // 初始化光敏电阻对应的ADC通道
	TIM3_PWM_Init();                                // 初始化定时器3的PWM输出，控制灯光
	HCSR04_Init();
	Buzzer_Init();
	Motor_Init();	
	
	Motor_SetSpeed(0);
	USART1_Init(9600);                              // 初始化串口1并设置9600波特率（连接蓝牙）
	DHT11_Init(&hdht11);                            // 初始化温湿度传感器结构体

	// 创建起始任务：它是所有任务的母亲，负责分配好资源后“自杀”
	xTaskCreate((TaskFunction_t )start_task, "start_task", STK_SIZE_NORMAL, NULL, START_TASK_PRIO, &StartTask_Handler);                 
	
	vTaskStartScheduler();                          // 启动任务调度器，系统正式开始运转
}

/*
*********************************************************************************************************
* 内部工具函数：覆盖式安全发送 (解决队列满导致的系统死锁)
*********************************************************************************************************
*/
void SafeQueueSend(QueueHandle_t xQueue, SystemMsg_t* msg) 
{
	if (xQueue == NULL) return;                     // 容错处理：如果队列不存在则返回
	
	// 尝试无阻塞发送，如果返回 errQUEUE_FULL 代表接收方处理太慢，队列塞满了
	if (xQueueSend(xQueue, msg, 0) == errQUEUE_FULL) 
	{
		SystemMsg_t dummy;                          // 定义临时变量用于“腾位置”
		xQueueReceive(xQueue, &dummy, 0);           // 强制取出队列中最老的一个数据并丢弃
		xQueueSend(xQueue, msg, 0);                 // 再次发送，确保最新的数据一定能进去
	}
}

/*
*********************************************************************************************************
* 任务逻辑实现
*********************************************************************************************************
*/

// --- 起始任务：创建所有队列和应用任务 ---
void start_task(void *pvParameters)
{
	taskENTER_CRITICAL();                           // 进入临界区，防止创建过程被中断打断

	// 1. 初始化所有通信队列
	xSystemDataQueue   = xQueueCreate(10, sizeof(SystemMsg_t)); // 总线队列深度10
	xBusinessQueue	   = xQueueCreate(10,  sizeof(SystemMsg_t)); // 报警队列深度5
	xOLEDQueue         = xQueueCreate(5,  sizeof(SystemMsg_t)); // 显示队列深度5
	xBT_TxQueue        = xQueueCreate(5,  sizeof(SystemMsg_t)); // 蓝牙队列深度5
	xCmdQueue          = xQueueCreate(5,  sizeof(uint8_t));     // 控制指令队列深度5
	
	// 2. 创建各层级功能任务
	xTaskCreate(dht11_task,            "dht11",    STK_SIZE_NORMAL, NULL, DHT11_TASK_PRIO,    NULL); // 温湿度 
	xTaskCreate(light_task,            "light",    STK_SIZE_NORMAL, NULL, LIGHT_TASK_PRIO,    NULL); // 光强
	xTaskCreate(ultrasonic_task,   "ultrasonic",   STK_SIZE_NORMAL, NULL, ULTRASONIC_TASK,    NULL); // 距离
	xTaskCreate(data_router_task,      "router",   STK_SIZE_NORMAL, NULL, ROUTER_TASK_PRIO,   NULL); // 路由
	xTaskCreate(business_control_task, "business", STK_SIZE_NORMAL, NULL, BUSINESS_TASK_PRIO, NULL); // 业务
	xTaskCreate(oled_display_task,     "oled",     STK_SIZE_LARGE,  NULL, EXEC_TASK_PRIO,     NULL); // 显示
	xTaskCreate(bluetooth_tx_task,     "bt_tx",    STK_SIZE_LARGE,  NULL, EXEC_TASK_PRIO,     NULL); // 蓝牙
	xTaskCreate(command_center_task,   "cmd_cntr", STK_SIZE_NORMAL, NULL, CMD_CENTER_PRIO,    NULL); // 指令中心

	vTaskDelete(NULL);                              // 资源分配完毕，删除起始任务自身
	taskEXIT_CRITICAL();                            // 退出临界区
}

// --- 第一层：采集层 (温湿度) ---
void dht11_task(void *pvParameters)
{
	SystemMsg_t msg;                                // 定义局部消息变量
	msg.id = MSG_ID_DHT11;                          // 设置这条消息的标签为温湿度
	while(1) 
	{                                      // 任务死循环
		if (DHT11_Read(&hdht11) == pdTRUE)
		{        // 读取成功后
			msg.data[0] = hdht11.temperature;       // 存入温度值
			msg.data[1] = hdht11.humidity;          // 存入湿度值
			
			xQueueSend(xSystemDataQueue, &msg, 0);  // 发送到系统总线队列
		}
		vTaskDelay(pdMS_TO_TICKS(1000));            // DHT11较慢，每1秒读一次即可
	}
}

// --- 第一层：采集层 (光照) ---
void light_task(void *pvParameters)
{
	SystemMsg_t msg;                                // 定义局部消息变量
	msg.id = MSG_ID_LIGHT;                          // 设置标签为光照
	while(1) 
	{                                      // 任务死循环
		uint32_t sum = 0;                           // 用于采样平均值计算
		for(uint8_t i=0; i<5; i++)
		{                // 循环采样5次
			sum += Get_Light_Value();               // 获取ADC原始值
			vTaskDelay(pdMS_TO_TICKS(10));          // 采样间隔10ms
		}
		msg.data[0] =(uint8_t)(100-(sum / 5) * 100 / 4095); // 转换为0-100%的光强百分比
		xQueueSend(xSystemDataQueue, &msg, 0);
		vTaskDelay(pdMS_TO_TICKS(300));             // 每300ms更新一次光强

	}
}
// --- 第一层：采集层 (距离) ---
void ultrasonic_task(void *pvParameters)
{
	SystemMsg_t msg;
	msg.id = MSG_ID_DISTANCE;
	float raw_dist = 0;

	while(1)
	{
			raw_dist = HCSR04_GetValue(); // 获取带小数的距离，如 15.24

			// 存储方案：
			// data[0] 存储厘米整数 (15)
			// data[1] 存储厘米小数位 (24)
			msg.data[0] = (uint8_t)raw_dist; 
			msg.data[1] = (uint8_t)((raw_dist - msg.data[0]) * 100);
			
			xQueueSend(xSystemDataQueue, &msg, 0);      // 发送到系统总线队列
			vTaskDelay(pdMS_TO_TICKS(300));             // 每300ms更新距离

	}

}

// --- 第二层：路由分发层 (高扩展性设计的核心) ---
void data_router_task(void *pvParameters) 
{
	SystemMsg_t rx_msg;                             // 接收缓存
	while(1)
	{                                      // 任务死循环
		// 阻塞等待总线上出现任何传感器数据
		if (xQueueReceive(xSystemDataQueue, &rx_msg, portMAX_DELAY) == pdPASS)
		{
			
			// 策略 A：所有传感器数据都必须送去“显示屏”和“蓝牙App”
			SafeQueueSend(xOLEDQueue, &rx_msg);     // 使用安全发送函数转发给OLED
			SafeQueueSend(xBT_TxQueue, &rx_msg);    // 使用安全发送函数转发给蓝牙
			SafeQueueSend(xBusinessQueue, &rx_msg); // <--- 让业务层获取所有数据

		}
	}
}


// --- 第三层：业务控制层 (智能逻辑处理) ---
void business_control_task(void *pvParameters) 
{
    SystemMsg_t msg;
    uint16_t pwm_val;
    
    // 使用静态变量持久保存各传感器的最新状态
    static uint8_t cur_temp = 0;
    static uint8_t cur_humi = 0;
    static uint8_t cur_light = 100;
    static uint8_t cur_dist = 0;

    while(1) 
    {
        // 阻塞等待任何一个传感器的数据包
        if (xQueueReceive(xBusinessQueue, &msg, portMAX_DELAY) == pdPASS) 
        {
            // 步骤 1：仅更新对应的数据状态，并执行独立的执行器逻辑（如电机、LED）
            switch (msg.id) 
            {
                case MSG_ID_DHT11:
                    cur_temp = msg.data[0];
                    cur_humi = msg.data[1];
                    
                    // 湿度自动控制风扇逻辑 (独立控制，不干扰蜂鸣器)
                    if (cur_humi >= current_thresholds.humidity_threshold) {
                        pwm_val = cur_humi * 10; 
                        if (pwm_val > 1000) pwm_val = 1000;
                        Motor_SetSpeed(pwm_val);
                    } else {
                        Motor_SetSpeed(0);
                    }		
                    break;
                    
				case MSG_ID_LIGHT:
				{
					// 现在 cur_light 和 App 上看到的数字完全一样 (越大代表越亮)
					cur_light = msg.data[0]; 
					
					// 兜底：防止用户在App把阈值设为0导致除零崩溃
					if (current_thresholds.light_threshold == 0) {
						current_thresholds.light_threshold = 1; 
					}

					// 逻辑 1：如果外界光强 >= 设定的关灯阈值，直接关灯
					if (cur_light >= current_thresholds.light_threshold) 
					{
						LED_SetBrightness(0); 
					} 
					// 逻辑 2：外界光强 < 设定的阈值，需要开灯，并根据差值调PWM
					else 
					{
						// 计算比例：当前光强 占 阈值的百分比
						// cur_light 越小 (外界越暗)，这个 ratio 就越接近 0
						// cur_light 越大 (外界较亮但没超阈值)，这个 ratio 就越接近 1
						float ratio = (float)cur_light / current_thresholds.light_threshold;

						// 根据比例调节PWM (外界越亮，ratio 越大，PWM 越小，灯越暗)
						if (ratio < 0.2f)      pwm_val = 1000; // 外界极暗：灯全功率最亮
						else if (ratio < 0.5f) pwm_val = 600;  // 外界偏暗：灯比较亮
						else if (ratio < 0.8f) pwm_val = 300;  // 外界一般：灯中等亮度
						else                   pwm_val = 100;  // 临近阈值：灯微亮兜底
						
						LED_SetBrightness(pwm_val);
					}
					break;
				}
             case MSG_ID_DISTANCE:
                    cur_dist = msg.data[0];
                    break;
                    
                default: break;
            }

            // 步骤 2：全域状态集中判断 (报警逻辑总闸)
            // 只有当所有条件都安全时，才会关闭蜂鸣器；只要有一个超标，就会持续鸣叫
            if (cur_temp >= current_thresholds.temp_threshold ||
                cur_dist <= current_thresholds.distance_threshold) 
            {
                Buzzer_ON();
            } 
            else 
            {
                Buzzer_OFF();
            }
        }
    }
}



// --- 第四层：执行层 (OLED显示) ---
void oled_display_task(void *pvParameters)
{
	SystemMsg_t msg;                                // 定义消息变量
	OLED_ShowString(1, 1, "T:   C  H:   %");       // 第一行预刷静态文字
	OLED_ShowString(2, 1, "Light:    %");          // 第二行预刷静态文字
	OLED_ShowString(3, 1, "Distance:");          // 第二行预刷静态文字
  
	while(1) 
		{                                      // 任务死循环
		if (xQueueReceive(xOLEDQueue, &msg, portMAX_DELAY) == pdPASS)
		{
			switch (msg.id) 
			{                       // 根据 ID 在不同位置刷新数值
				case MSG_ID_DHT11:                  // 刷新温湿度
					OLED_ShowNum(1, 3, msg.data[0], 2); // 写入温度
					OLED_ShowNum(1, 11, msg.data[1], 2); // 写入湿度
					break;
				case MSG_ID_LIGHT:                  // 刷新光强
					OLED_ShowNum(2, 8,msg.data[0], 2); // 写入光强百分比
					break;
				case MSG_ID_DISTANCE:
					OLED_ShowNum(4, 7, msg.data[0], 3);   // 显示整数部分
					OLED_ShowString(4, 10, ".");          // 显示小数点
					OLED_ShowNum(4, 11, msg.data[1], 2);  // 显示两位小数
					OLED_ShowString(4, 13, "cm");
					break;
				default: break;
			}
		}
	}
}

// --- 第四层：执行层 (蓝牙发送) ---
void bluetooth_tx_task(void *pvParameters) 
{
SystemMsg_t msg;
char str_buf[64]; 

while(1) 
{
	// 阻塞获取队列数据
	if (xQueueReceive(xBT_TxQueue, &msg, portMAX_DELAY) == pdPASS) 
	{
		memset(str_buf, 0, sizeof(str_buf)); 

		if (msg.id == MSG_ID_DHT11) 
		{
			// Java解析逻辑：indexOf("T:")+2 到 indexOf("H:") 截取温度
			// 必须严格遵守 "T:xx H:xx\n" 格式
			sprintf(str_buf, "T:%d H:%d\n", msg.data[0], msg.data[1]);
		} 
		else if (msg.id == MSG_ID_LIGHT)
		{
			// Java解析逻辑：data.contains("Light:") 且跳过 6 个字符
			sprintf(str_buf, "Light:%d\n", msg.data[0]);
		}
		else if (msg.id == MSG_ID_DISTANCE) 
		{
			// Java解析逻辑：indexOf("Dist:")+5 到 indexOf("cm")
			// 必须包含 Dist: 前缀和 cm 后缀
			sprintf(str_buf, "Dist:%d.%02dcm\n", msg.data[0], msg.data[1]);
		}

		// 发送给 App
		if (strlen(str_buf) > 0) {
			USART_SendString(USART1, str_buf);
		}
	}
}
}





void command_center_task(void *pvParameters)
{
uint8_t cmd;
while(1) 
{
       if (xQueueReceive(xCmdQueue, &cmd, portMAX_DELAY) == pdPASS)
        {
            if (cmd == 0xFE) 
            {
                // 解析 App 发来的指令
                if (strncmp(g_UsartRxBuf, "SET_T:", 6) == 0) {
                    uint8_t val = (uint8_t)atoi(&g_UsartRxBuf[6]);
                    // 调用你的专门函数：更新温度，其他保持现状
                    update_thresholds(val, current_thresholds.humidity_threshold, 
                                      current_thresholds.distance_threshold, current_thresholds.light_threshold);
                } 
                else if (strncmp(g_UsartRxBuf, "SET_H:", 6) == 0) {
                    uint8_t val = (uint8_t)atoi(&g_UsartRxBuf[6]);
                    // 更新湿度，其他保持现状
                    update_thresholds(current_thresholds.temp_threshold, val, 
                                      current_thresholds.distance_threshold, current_thresholds.light_threshold);
                } 
                else if (strncmp(g_UsartRxBuf, "SET_L:", 6) == 0) { 
                    uint8_t val = (uint8_t)atoi(&g_UsartRxBuf[6]);
                    // 更新光强，其他保持现状
                    update_thresholds(current_thresholds.temp_threshold, current_thresholds.humidity_threshold, 
                                      current_thresholds.distance_threshold, val);
                }
                else if (strncmp(g_UsartRxBuf, "SET_D:", 6) == 0) {
                    uint8_t val = (uint8_t)atoi(&g_UsartRxBuf[6]);
                    // 更新距离，其他保持现状
                    update_thresholds(current_thresholds.temp_threshold, current_thresholds.humidity_threshold, 
                                      val, current_thresholds.light_threshold);
                }
                
                memset(g_UsartRxBuf, 0, sizeof(g_UsartRxBuf)); 
            }
			else 
				{
					// 处理 Android 端发送的单字节控制指令 
					if(cmd == 0x01) LED1_ON();
					else if(cmd == 0x00) LED1_OFF();
				}

	}
}
}
