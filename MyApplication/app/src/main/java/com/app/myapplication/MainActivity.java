package com.app.myapplication;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String JDY31_MAC_ADDRESS = "03:3A:DC:A2:C9:D1"; // 目标蓝牙模块的物理地址
    private static final String PREFS_NAME = "SensorPrefs"; // 本地轻量级存储的文件名

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothConnectionThread bluetoothThread;
    private SensorDatabaseHelper dbHelper;
    // 界面控件引用
    private TextView tvTemp, tvHumi, tvLight, tvDist, tvStatus, tvChartTitle, tvThresholdTitle, tvWarning;
    private Button btnToggleHistory, btnSetThreshold;
    private LineChart realtimeChart;
    private EditText etThreshold;
    // 状态与缓存变量
    private String currentChartType = "温度"; // 默认显示的图表类型
    private boolean isHistoryMode = false;  // 标记当前是否处于历史记录查看模式
    private StringBuilder dataBuffer = new StringBuilder(); // 用于拼接蓝牙串口发来的碎片化数据（解决粘包/分包问题）
    // 阈值变量与最新数据缓存 (-1f 代表未设置报警阈值或暂无数据)
    private float tempThreshold = -1f, humiThreshold = -1f, lightThreshold = -1f, distThreshold = -1f;
    private float lastTemp = -1f, lastHumi = -1f, lastLight = -1f, lastDist = -1f;
    // 均值滤波累加器 (用于将5秒内的数据求平均，滤除硬件传感器偶尔的跳动干扰)
    private float sumTemp = 0, sumHumi = 0, sumLight = 0, sumDist = 0;
    private int dataCount = 0;
    private final int DB_SAVE_INTERVAL = 5000; // 定时任务间隔：5000毫秒 (5秒)
    private Handler dbSaveHandler = new Handler(Looper.getMainLooper()); // 用于抛出定时任务的主线程 Handler


    //-----------------初始化-------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 在数据持久层方面，我实例化了自定义的数据库辅助类 SensorDatabaseHelper。
        // 通过传入当前的 Activity Context，建立起应用层与底层 SQLite 数据库通信的句柄。”
        dbHelper = new SensorDatabaseHelper(this);
        // 封装好的各个初始化模块
        initViews();//绑定视图
        initChart();//初始化图表
        setupCardListeners();//设置卡片点击监听
        loadThresholdsFromLocal();//加载本地阈值
        //。这一步是获取蓝牙硬件的本地适配器引用
        // 它是单例模式在 Android 系统层的典型应用，为后续设备扫描和建立 RFCOMM 连接提供了必要的系统级 API 支持。”
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        // 设置点击监听
        findViewById(R.id.btnConnect).setOnClickListener(v -> connectToJDY31());
        findViewById(R.id.btnL1On).setOnClickListener(v -> sendCommand((byte) 0x01));
        findViewById(R.id.btnL1Off).setOnClickListener(v -> sendCommand((byte) 0x00));
        btnToggleHistory.setOnClickListener(v -> toggleHistoryMode());
        btnSetThreshold.setOnClickListener(v -> handleThresholdSetup());
        // 启动后台定时任务：延迟 DB_SAVE_INTERVAL 毫秒后执行 dbSaveRunnable
        dbSaveHandler.postDelayed(dbSaveRunnable, DB_SAVE_INTERVAL);
    }
    //-----绑定可点击的事件-------------------------------------------
    private void initViews() {
        tvTemp = findViewById(R.id.tvTemp);
        tvHumi = findViewById(R.id.tvHumi);
        tvLight = findViewById(R.id.tvLight);
        tvDist = findViewById(R.id.tvDist);
        tvStatus = findViewById(R.id.tvStatus);
        tvChartTitle = findViewById(R.id.tvChartTitle);
        tvThresholdTitle = findViewById(R.id.tvThresholdTitle);
        realtimeChart = findViewById(R.id.realtimeChart);
        btnToggleHistory = findViewById(R.id.btnToggleHistory);
        etThreshold = findViewById(R.id.etThreshold);
        btnSetThreshold = findViewById(R.id.btnSetThreshold);
        tvWarning = findViewById(R.id.tvWarning);
    }

    //-----创建图表-----1.配置xy轴2.绑定数据集----------------------
    private void initChart() {
        //这个图表库有个毛病，默认会在图表的右下角贴一张难看的“出厂标签”（Description）。这句话就是一把撕掉这个标签，让画板干干净净。
        realtimeChart.getDescription().setEnabled(false);
        //设置图表的空视图状态 (Empty View State)。在底层数据源为空时，提供友好的占位提示，这是经典的防御性编程和 UX（用户体验）设计规范。
        realtimeChart.setNoDataText("暂无数据");
        //实例化一个空的折线数据集对象 (LineData)，并将其与图表视图对象完成内存绑定
        // 。这为后续实时数据的动态注入（依赖此 data 对象引用）做好了底层容器准备。
        LineData data = new LineData(); // 创建空的数据集
        realtimeChart.setData(data); // 绑定数据集到图表
        //获取图表 X 坐标轴对象的单例引用，并通过枚举值 BOTTOM 重新定义坐标轴标签的物理渲染位置，以符合通用数据可视化的设计惯例。
        XAxis xAxis = realtimeChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM); // X轴放置在底部
        realtimeChart.getAxisRight().setEnabled(false); // 禁用右侧的Y轴
    }

    //----设置卡片的点击监听器--------------------------------------------
    private void setupCardListeners() {
        // 点击哪个卡片，就调用 switchChartType 切换下面折线图的数据源
        findViewById(R.id.layoutTemp).setOnClickListener(v -> switchChartType("温度"));
        findViewById(R.id.layoutHumi).setOnClickListener(v -> switchChartType("湿度"));
        findViewById(R.id.layoutLight).setOnClickListener(v -> switchChartType("光强"));
        findViewById(R.id.layoutDist).setOnClickListener(v -> switchChartType("距离"));
    }



//蓝牙连接------------
    //连接到 JDY-31 蓝牙模块1.检查蓝牙适配器是否可用2.创建并启动子线程去处理耗时的 Socket 连接3.启动子线程，开始尝试连接
    @SuppressLint("MissingPermission")
    private void connectToJDY31() {
        // 检查蓝牙适配器是否可用
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            tvStatus.setText("状态: 请先开启手机蓝牙和定位权限");
            return;
        }
        // 将一个字符串格式的 MAC 地址，转化为操作系统认可的、可以用来建立 Socket 连接的 BluetoothDevice 对象。
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(JDY31_MAC_ADDRESS);
        // 创建并启动子线程去处理耗时的 Socket 连接
        bluetoothThread = new BluetoothConnectionThread(device, mHandler);
        // 启动子线程，开始尝试连接
        bluetoothThread.start();
        tvStatus.setText("状态: 正在尝试连接...");
    }

    //----从本地获取阈值----1.从 配置文件获取数据2.设置默认值
    private void loadThresholdsFromLocal() {
        //获取轻量级存储（键值对）的读写操作句柄 (实例)。调用 Android 系统的本地轻量级持久化方案。
        // getSharedPreferences 会在手机底层沙盒中查找或创建一个 XML 配置文件。
        // MODE_PRIVATE 定义了该文件的访问权限，确保数据私有化，防止跨应用的数据越权读取，保证了数据安全。
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 根据指定的 Key (键名) 获取保存的 Float 值。如果本地文件中没有这个 Key，则强制返回参数传入的缺省值 (defValue)
        tempThreshold = sp.getFloat("temp", -1f);
        humiThreshold = sp.getFloat("humi", -1f);
        lightThreshold = sp.getFloat("light", -1f);
        distThreshold = sp.getFloat("dist", -1f);

        switchChartType("温度"); // 初始化完毕后，默认高亮并显示温度图表
    }

    //------连接成功第一次同步保存的阈值到单片机
    private void syncSavedThresholdsToMCU() {
        // 注意：这里使用 Handler.postDelayed 故意制造了延时！
        // 因为单片机的串口接收缓冲区有限，如果瞬间连发4条指令，容易造成单片机端“粘包”或死机
        if (tempThreshold != -1f) sendThresholdToMCU("温度", tempThreshold);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (humiThreshold != -1f) sendThresholdToMCU("湿度", humiThreshold);
        }, 100); // 延时 100 毫秒发送

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (lightThreshold != -1f) sendThresholdToMCU("光强", lightThreshold);
        }, 200); // 延时 200 毫秒发送

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (distThreshold != -1f) sendThresholdToMCU("距离", distThreshold);
        }, 300); // 延时 300 毫秒发送
    }

    //接收蓝牙数据--1.连接成功--加载阈值2.连接失败--显示错误信息3.数据接收--------对数据进行粘包之后调用解析方法4.实例化一个绑定在主线程（UI线程）的消息轮询器（Looper）上的 Handler 对象。
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BluetoothConnectionThread.MESSAGE_CONNECTED:
                    tvStatus.setText("状态: 已连接 (运行中)");
                    tvStatus.setTextColor(Color.GREEN);

                    syncSavedThresholdsToMCU(); // 建立连接后，立刻下发一次旧的阈值

                    break;

                case BluetoothConnectionThread.MESSAGE_CONNECTION_FAILED:
                    tvStatus.setText("状态: 连接失败");
                    tvStatus.setTextColor(Color.RED);
                    break;

                case BluetoothConnectionThread.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;

                    // 将新收到的字节转为字符串并追加到缓冲区 (解决串口分包发送的问题)
                    dataBuffer.append(new String(readBuf, 0, msg.arg1));

                    int newlineIndex;
                    // 循环查找换行符 '\n'，以此作为一条完整数据帧的结束标志
                    while ((newlineIndex = dataBuffer.indexOf("\n")) != -1) {

                        // 提取出包含完整一帧的字符串（去除两端空白字符）
                        String completeData = dataBuffer.substring(0, newlineIndex).trim();

                        // 简单校验：只解析包含我们认识的特征码的数据
                        if (completeData.contains("T:") || completeData.contains("H:") ||
                                completeData.contains("Light:") || completeData.contains("Dist:")) {
                            parseSensorData(completeData); // 丢给解析函数
                        }
                        // 处理完毕后，从缓冲区中把这帧数据删掉（包括换行符）
                        dataBuffer.delete(0, newlineIndex + 1);
                    }
                    break;
            }
        }
    };



    //------解析原始数据1.更新ui2.累计5s数据3.更新实时图表4.更新报警状态

    private void parseSensorData(String data) {
        if (data == null || data.isEmpty()) return;

        float valueToChart = -1; // 准备交给图表去画的具体数值
        boolean isMatch = false; // 标记这条数据是否属于当前屏幕上正在显示的图表类型

        try {
            // 解析温湿度 (假设单片机发来的格式是: "T:25.5 H:60.0")
            if (data.contains("T:") || data.contains("H:")) {// 如果包含"T:"或"H:"
                for (String p : data.split(" ")) { // 按空格切分
                    if (p.startsWith("T:")) {// 如果以"T:"开头
                        lastTemp = Float.parseFloat(p.replace("T:", ""));//将"T:"替换为空字符串，将剩下字符串转数字
                        tvTemp.setText(String.format("%.0f ℃", lastTemp)); // 更新UI文本
                        if (currentChartType.equals("温度")) { valueToChart = lastTemp; isMatch = true; } // 如果当前在看温度图，就标记它
                    }
                    // 如果以"H:"开头
                    if (p.startsWith("H:")) {
                        lastHumi = Float.parseFloat(p.replace("H:", ""));
                        tvHumi.setText(String.format("%.0f %%", lastHumi));// 更新UI文本
                        if (currentChartType.equals("湿度")) { valueToChart = lastHumi; isMatch = true; }// 如果当前在看湿度图，就标记它
                    }
                }
            }
            // 解析光强 (假设格式: "Light:85")
            else if (data.contains("Light:")) {
                lastLight = Float.parseFloat(data.replace("Light:", ""));
                tvLight.setText(String.format("%.0f %%", lastLight));
                if (currentChartType.equals("光强")) { valueToChart = lastLight; isMatch = true; }
            }
            // 解析距离 (假设格式: "Dist:15.2cm")
            else if (data.contains("Dist:")) {
                lastDist = Float.parseFloat(data.replace("Dist:", "").replace("cm", "")); // 连续替换，把字母都删掉
                tvDist.setText(String.format("%.2f cm", lastDist));
                if (currentChartType.equals("距离")) { valueToChart = lastDist; isMatch = true; }
            }
        } catch (Exception e) {
            // 捕获转换异常，防止单片机发来乱码导致APP崩溃
            Log.e("DATA", "Parsing error: " + data);
        }

        // 把本次有效数据累加到滤波器中，用于后台定时任务计算平均值
        if (lastTemp != -1f && lastHumi != -1f && lastLight != -1f && lastDist != -1f) {
            sumTemp += lastTemp; sumHumi += lastHumi; sumLight += lastLight; sumDist += lastDist;
            dataCount++; // 计数器+1
        }

        // 仅在“实时模式”下，且解析到了当前图表需要的数据时，才触发图表重绘
        if (!isHistoryMode && isMatch && valueToChart != -1) updateChart(valueToChart);

        // 每次收到新数据都检查一遍是否越界报警
        checkAllThresholds();
    }

    //----根据数据判断是否弹出报警提示----------------------------------
    private void checkAllThresholds() {
        StringBuilder warningMsg = new StringBuilder(); // 用于拼接多条警告信息

        // 逻辑：当前值 >= 报警值触发报警（可根据业务需求修改逻辑方向）
        if (tempThreshold != -1f && lastTemp >= tempThreshold) warningMsg.append("温度过高 ");
        if (humiThreshold != -1f && lastHumi >= humiThreshold) warningMsg.append("湿度过高 ");
        if (lightThreshold != -1f && lastLight != -1f && lastLight < lightThreshold) warningMsg.append("光线过暗 ");
        if (distThreshold != -1f && lastDist <= distThreshold) warningMsg.append("距离过近 ");

        // UI 反馈：如果有警告信息，组合前缀并显示；如果没有，隐藏警告控件
        tvWarning.setText(warningMsg.length() > 0 ? "⚠️ 警告：" + warningMsg.toString() : "");
        tvWarning.setVisibility(warningMsg.length() > 0 ? View.VISIBLE : View.GONE);
    }




    //------卡片触发事件的具体实现-1.显示当前的阈值2.根据模式显示不同的图表-----------------------------------------
    private void switchChartType(String type) {
        currentChartType = type;

        // 动态修改标题
        tvChartTitle.setText(type + (isHistoryMode ? " 历史趋势" : " 实时曲线"));
        if (tvThresholdTitle != null) tvThresholdTitle.setText("报警阈值设置 (当前: " + type + ")");// 更新报警阈值标题

        // 切换类型时，把新类型对应的阈值“回显”到输入框里
        float currentTh = getThresholdForCurrentType();// 获取当前类型的报警阈值-----
        if (currentTh != -1f) {
            etThreshold.setText(type.equals("距离") ? String.format("%.2f", currentTh) : String.valueOf((int) currentTh));
        } else {
            etThreshold.setText(""); // 没设置则清空
        }

        // 彻底清空旧图表的连线
        realtimeChart.clear();
        // 根据当前模式重新加载画面
        if (isHistoryMode) showHistoryChart(); else initChart();
    }
    //------获取当前传感器的阈值
    private float getThresholdForCurrentType() {
        switch (currentChartType) {
            case "温度": return tempThreshold;
            case "湿度": return humiThreshold;
            case "光强": return lightThreshold;
            case "距离": return distThreshold;
            default: return -1f;
        }
    }

    //------更改当前传感器的阈值
    private void saveThresholdForCurrentType(float val) {
        // 根据当前的 Tab，把值塞给对应的变量
        switch (currentChartType) {
            case "温度": tempThreshold = val; break;
            case "湿度": humiThreshold = val; break;
            case "光强": lightThreshold = val; break;
            case "距离": distThreshold = val; break;
        }
    }

    //设置阈值按钮的实现-1.获取用户输入的阈值2.判断是否为有效数字3.保存到本地数据库4.发送到 MCU5.更新 Toast 提示6.检查所有阈值
    private void handleThresholdSetup() {
        //获取 EditText 的文本序列并转化为 String，随后调用 trim() 方法去除首尾的空白字符（包括空格、回车等）
        String input = etThreshold.getText().toString().trim();//1.获取用户输入的阈值

        if (!input.isEmpty()) { // 如果用户输入了内容
            try {
                float val = Float.parseFloat(input); // 将字符串转换为浮点数
                saveThresholdForCurrentType(val);    // 保存到本地数据库
                saveThresholdsToLocal();             // 触发本地持久化。将内存态的数据异步写入 XML 文件中，实现状态固化。
                sendThresholdToMCU(currentChartType, val); // 调用应用层通信接口。执行协议组装并向底层 Socket 输出流下发控制指令。

                // 友好的 Toast 弹窗提示
                String displayVal = currentChartType.equals("距离") ? String.format("%.2f", val) : String.valueOf((int)val);
                Toast.makeText(this, currentChartType + " 阈值已存并设置: " + displayVal, Toast.LENGTH_SHORT).show();

                checkAllThresholds(); // 立即做一次状态判定（也许刚设的阈值立马就触发了报警）
            } catch (Exception e) { // 用户乱输了字母
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        } else { // 如果用户把输入框清空了再点设置，意味着【取消该阈值】
            saveThresholdForCurrentType(-1f);//阈值设为-1，表示不触发报警
            saveThresholdsToLocal();//触发本地持久化。将内存态的数据异步写入 XML 文件中，实现状态固化。
            sendThresholdToMCU(currentChartType, -1f);// 调用应用层通信接口。执行协议组装并向底层 Socket 输出流下发控制指令。
            Toast.makeText(this, "已取消 " + currentChartType + " 阈值", Toast.LENGTH_SHORT).show();
            checkAllThresholds();// 立即做一次状态判定（也许刚设的阈值立马就触发了报警）
        }
    }

    //保存所有阈值到本地数据库-1.获取当前所有传感器的阈值2.将阈值写入 SharedPreferences XML 文件3.提交写入磁盘4.关闭 SharedPreferences 编辑器
    private void saveThresholdsToLocal() {
        //获取指定名称和私有模式（MODE_PRIVATE）的 SharedPreferences 实例。
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        //必须通过调用 edit() 方法，
        // 获取一个 SharedPreferences.Editor 接口实例，
        // 才能开启针对该 XML 文件的内存级写事务 (Write Transaction)。
        SharedPreferences.Editor editor = sp.edit();
        // 键值对写入
        editor.putFloat("temp", tempThreshold);
        editor.putFloat("humi", humiThreshold);
        editor.putFloat("light", lightThreshold);
        editor.putFloat("dist", distThreshold);
        editor.apply(); //异步提交写入磁盘，确保数据及时持久化 不用commit()--阻塞
    }

    //------发送阈值到单片机-1.根据传感器类型拼接指令字符串2.将指令字符串转换为字节数组3.通过蓝牙线程发送指令
    private void sendThresholdToMCU(String type, float val) {
        if (bluetoothThread == null) return;

        float safeVal = val;
        // 逻辑处理：如果是取消阈值（值为-1f），我们通过发送一个单片机“永远达不到”的极限值来变相关闭单片机端的报警
        if (val == -1f) {
            switch (type) {
                case "温度": case "湿度": safeVal = 100f; break; // 环境温度/湿度设为100才报警，相当于关闭报警
                case "光强": case "距离": safeVal = 0f; break;   // 光强/距离降到0才报警，相当于关闭报警
            }
        }

        String command = "";
        // 按照与单片机约定的通信协议拼接字符串，必须以 \n 结尾
        switch (type) {
            case "温度": command = "SET_T:" + safeVal + "\n"; break;
            case "湿度": command = "SET_H:" + safeVal + "\n"; break;
            case "光强": command = "SET_L:" + safeVal + "\n"; break;
            case "距离": command = "SET_D:" + String.format("%.2f", safeVal) + "\n"; break;
        }
        // 转为字节数组发送
        bluetoothThread.sendBytes(command.getBytes());
    }

    //------开灯与关灯的简单指令
    private void sendCommand(byte b) {
        if (bluetoothThread != null) bluetoothThread.sendBytes(new byte[]{b});
    }




    //------切换历史模式按钮的实现-1.切换按钮文字2.切换图表标题3.根据模式显示不同的图表
    private void toggleHistoryMode() {
        isHistoryMode = !isHistoryMode; // 翻转布尔标记
        realtimeChart.clear(); // 抹平画板

        if (isHistoryMode) {
            // 进历史模式
            btnToggleHistory.setText("返回实时监控");
            tvChartTitle.setText(currentChartType + " 历史趋势");
            showHistoryChart(); // 去查数据库画图
        } else {
            // 退回实时模式
            btnToggleHistory.setText("查看历史记录");
            tvChartTitle.setText(currentChartType + " 实时曲线");
            initChart(); // 重新搭一个空画板等实时数据来
        }
    }

    //------更新实时曲线的绘制-
    private void updateChart(float value) {
        LineData data = realtimeChart.getData(); // 获取图表大盘
        if (data != null) {
            ILineDataSet set = data.getDataSetByIndex(0); // 找第0条线
            if (set == null) {
                set = createSet();// 如果是第一笔数据，先创建这根线的画笔属性
                data.addDataSet(set);// 把这根线添加到图表数据里
            }

            // X轴：当前线上点的总数 (实现自增)； Y轴：传进来的真实数值
            data.addEntry(new Entry(set.getEntryCount(), value), 0);// 把最新点添加到这根线里

            // 必须通知底层系统数据已变！
            data.notifyDataChanged();// 通知图表数据已更新
            realtimeChart.notifyDataSetChanged();// 通知图表重绘

            // 核心动画：限制屏幕最多显示40个点，强制视图移动到最新的那个点，实现波形图“向左流动”的视觉效果
            realtimeChart.setVisibleXRangeMaximum(40);// 限制屏幕最多显示40个点
            realtimeChart.moveViewToX(data.getEntryCount());// 强制视图移动到最新的那个点
        }
    }

    //-----历史模式的图表绘制-1.从数据库查询历史数据2.配置图表样式------
    private void showHistoryChart() {
        if (dbHelper == null) return;

        // 查库操作：获取最近 60 条记录并封装成 Entry 列表
        List<Entry> historyEntries = dbHelper.getHistoryEntries(currentChartType, 60);

        if (historyEntries.isEmpty()) { // 防空保护
            realtimeChart.setNoDataText("暂无历史数据");
            realtimeChart.invalidate();//强制刷新一下屏幕（invalidate），直接退回去
            return;
        }

        // 配置历史记录线条的样式 (区别于实时的蓝色线条)
        LineDataSet dataSet = new LineDataSet(historyEntries, currentChartType + " 历史记录");
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER); // 贝塞尔平滑曲线
        dataSet.setLineWidth(2f); // 线宽
        dataSet.setColor(Color.parseColor("#FF9800")); // 橙色
        dataSet.setDrawCircles(true); // 历史图我们把具体的数据节点(小圆圈)画出来
        dataSet.setCircleRadius(3f);//设置圆的半径
        dataSet.setDrawValues(false); // 不显示具体的数字，避免屏幕太挤

        realtimeChart.setData(new LineData(dataSet)); // 重新塞给图表
        realtimeChart.setVisibleXRangeMaximum(60); // 视野放大到60个点
        realtimeChart.fitScreen(); // 重置缩放，让它贴合手机屏幕
        realtimeChart.invalidate(); // 触发重绘
    }

    //------设置描绘实时曲线的样式
    private LineDataSet createSet() {
        LineDataSet set = new LineDataSet(null, currentChartType);//实例化一个初始状态为空的折线数据集对象
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER); // 平滑
        set.setLineWidth(2.5f);// 线宽
        set.setColor(Color.BLUE); // 实时曲线用深蓝色
        set.setDrawCircles(false); // 实时滚动不需要画圆圈节点，提高性能
        set.setDrawValues(false);// 不显示具体的数字，避免屏幕太挤
        return set;
    }



    //-----定时器-------------------------------------------
    private Runnable dbSaveRunnable = new Runnable() {
        @Override
        public void run() {
            // 只有在这5秒内收到过单片机的数据，才进行存储
            if (dataCount > 0) {
                if (dbHelper != null) {
                    // 取总和除以次数 = 均值滤波。这是一种常见的硬件抗干扰算法，过滤突变的杂波
                    dbHelper.insertAveragedData(sumTemp / dataCount, sumHumi / dataCount, sumLight / dataCount, sumDist / dataCount);
                }

                // 存完后，将累加器和计数器全部清零，准备迎接下一个 5 秒
                sumTemp = 0; sumHumi = 0; sumLight = 0; sumDist = 0; dataCount = 0;

                // 如果当前正好停留在【历史图表】界面，每次存完新数据顺手刷新一下图表，实现“动态生长的历史曲线”
                if (isHistoryMode) showHistoryChart();
            }

            // 核心语句：执行完本次后，再给自己挂一个 5 秒后的闹钟，形成无限循环
            dbSaveHandler.postDelayed(this, DB_SAVE_INTERVAL);
        }
    };


    //-----释放资源-------------------------------------------
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 非常重要：砸掉闹钟！如果不移除回调，Activity 关闭后这个 Runnable 还在无限循环，会导致严重的内存泄漏
        dbSaveHandler.removeCallbacks(dbSaveRunnable);

        // 关门谢客，释放底层资源
        if (dbHelper != null) dbHelper.close();
        if (bluetoothThread != null) bluetoothThread.cancel();
    }
}