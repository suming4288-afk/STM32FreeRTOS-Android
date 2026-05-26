package com.app.myapplication;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * 蓝牙连接与通信线程 (专门负责在后台干苦力的“子线程”)
 * 负责：连接 JDY-31 蓝牙模块、死循环接收传感器数据、发送控制指令
 */
public class BluetoothConnectionThread extends Thread {

    private static final String TAG = "BluetoothCOM";

    /*
     * 方法调用: UUID.fromString(String name)
     * 传入参数: 1个 String，代表标准格式的 UUID 字符串。
     * 执行操作: 解析该字符串，验证其格式，并将其转换为系统底层的 128 位 UUID 对象。
     * 返回结果: 返回一个 java.util.UUID 类的实例对象。
     */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static final int MESSAGE_READ = 1;
    public static final int MESSAGE_CONNECTED = 2;//连接成功
    public static final int MESSAGE_CONNECTION_FAILED = 3;

    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    // Socket 是桥，
    // Stream 是路，
    // Handler 是邮差，
    // Thread 是干苦力的长工，
    // try-catch 是保命的防弹衣。

    // 构造方法--通过创建套接字获取输出输入流
    @SuppressLint("MissingPermission")//这是一个注解，用于压制系统对缺少蓝牙权限检查的静态代码警告。
    //外部传入的目标蓝牙设备对象和用于跨线程通信的 Handler 实例
    public BluetoothConnectionThread(BluetoothDevice device, Handler handler) {
        //声明一个局部的临时 Socket 变量，用于安全的异常捕获处理。
        BluetoothSocket tmp = null;
        //把大门外递进来的“邮差” handler，妥善保管到自己家里的抽屉 mHandler 里，以后就用家里的这个了。
        mHandler = handler;
        try {
 //我要用 SPP_UUID 这个暗号，在你身上开一个基于 RFCOMM 协议的串口插座。” 如果成功了，就把做好的插座给刚才准备的临时变量 tmp。
            tmp = device.createRfcommSocketToServiceRecord(SPP_UUID);
            //如果上面那句失败了（比如蓝牙芯片坏了），就会跳到这里，抓到一个叫 e 的错误对象。
        } catch (IOException e) {
            Log.e(TAG, "Socket 套接字创建失败", e);
        }
        //“为了保证线程安全，我使用了 try-catch 块来捕获 createRfcommSocketToServiceRecord 可能产生的 IO 异常，并将成功实例化的 Socket 赋值给不可变的成员变量 mmSocket。”
        mmSocket = tmp;
        //和刚才造插座一样，先准备两个临时的空管子，一个用来接水/收数据（tmpIn），一个用来排水/发指令（tmpOut）。
        InputStream tmpIn = null;
        OutputStream tmpOut = null;
        try {
            //安全第一，先确认一下刚才第三块的插座是不是真的造成功了。如果不为空，才继续往下走。
            if (mmSocket != null) {
                // 核心步骤 2：获取输入输出流
                //从插座里，拔出那根专门用来接收数据的管子，交给临时变量 tmpIn。
                tmpIn = mmSocket.getInputStream();
                //从插座里，拔出那根专门用来发送数据的管子，交给临时变量 tmpOut。
                tmpOut = mmSocket.getOutputStream();
            }
            //如果拔管子失败了，就在后台打印一条错误日志。
        } catch (IOException e) {
            Log.e(TAG, "获取输入输出流失败", e);
        }
        //把临时进水管转交给正式的全局变量 mmInStream。
        mmInStream = tmpIn;
        //把临时出水管转交给正式的全局变量 mmOutStream。
        mmOutStream = tmpOut;
    }

//发起真实的物理请求--成功了就发送消息给主线程用加载历史数据信息
    //这是一个注解，用于压制系统对缺少蓝牙权限检查的静态代码警告。
    @SuppressLint("MissingPermission")
    //意思是“重写/覆盖”。由于你的类继承了 Android 系统的 Thread（线程）类，系统规定你必须写一个名字一模一样的 run 方法。
    @Override
    //这是子线程的心脏起搏器。在别的地方（比如你主界面点击连接按钮时）调用了 bluetoothThread.start() 之后，程序就会瞬间跳到这里，开始在后台独立执行这里面的代码。
    public void run() {
        try {
            // 核心步骤 3：发起真实的物理连接请求
            // 这是一个阻塞方法。线程会停在这里，直到连接成功，或者抛出异常（连接失败或超时
        //这是一个**“阻塞 (Blocking)”方法。意思是，当代码执行到这一行时，这个子线程会彻底停下来死等**（可能等1秒，也可能等10秒），
            // 直到硬件接通了，或者彻底连不上报错了，它才会走到下一行。
            mmSocket.connect();

            // 核心步骤 4：通知主线程连接成功
           //如果 connect() 阻塞结束后没有抛出异常，代表物理链路建立成功。
            // 此时我通过 Handler 机制，跨线程发送一个常量标识符给 UI 线程，触发主界面的状态文字更新和阈值下发同步。
            mHandler.sendEmptyMessage(MESSAGE_CONNECTED);

        } catch (IOException connectException) {
            // 如果连接失败，会跳到这里执行

            // 通知主线程连接失败
            mHandler.sendEmptyMessage(MESSAGE_CONNECTION_FAILED);
            try {
                // 这是我的容错与资源回收机制。一旦捕获到连接超时的 IO 异常，我会第一时间通知主线程更新 UI 为红色报错状态。
                // 同时，强制执行 socket.close() 释放底层系统端口，最后用 return 优雅地终止当前子线程，防止内存泄漏。
                mmSocket.close();
            } catch (IOException ignored) {}

            return; // 结束整个线程
        }

        // 核心步骤 5：启动数据接收循环
        // 如果连接成功，就开始死循环监听输入流，等待单片机发来的数据。
        receiveData();
    }

    private void receiveData() {
        //准备一个容量为 1024 字节（1KB）的“空水桶”。注意，这个桶是在死循环外面创建的，这样不用每次循环都造一个新桶，节省内存。
        byte[] buffer = new byte[1024];
        int bytes;
        while (true) {
            try {
                //拿着水桶在进水管（mmInStream）下面死等。这就是著名的阻塞。
                // 如果单片机不发数据，程序就停在这行不走；一旦发了，水流进桶里，并且告诉你到底接了多少滴水（bytes）。
                bytes = mmInStream.read(buffer);
                //水接到了，叫邮差（mHandler）拿一个标准的快递盒（Message），
                // 在盒子上贴上标签 MESSAGE_READ，写上重量 bytes，把装满水的桶 buffer 放进去。
                Message readMsg = mHandler.obtainMessage(MESSAGE_READ, bytes, -1, buffer);
                // 把这个快递盒（Message）发给 UI 线程，触发主界面的更新。邮差出发！把盒子送回给主线程。
                readMsg.sendToTarget();

            } catch (IOException e) {
                Log.e(TAG, "连接中断，无法继续读取数据", e);
                break;
            }
        }
    }


    public void sendBytes(byte[] bytes) {
        //发送前先摸一下出水管还在不在。如果管子没接好，直接退出，防止程序崩溃。
        if (mmOutStream == null) return;

        try {
            //把主线程传过来的字节（比如你设置的温度阈值指令），一股脑塞进出水管里。
            mmOutStream.write(bytes);

            //flush 是“冲水”的意思。有些管道内部有缓冲机制，塞进去的东西可能不会立刻流走。这句话就是强制系统：“别攒着了，立刻给我冲下去发给单片机！”
            mmOutStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "发送字节数据失败", e);
        }
    }


    public void cancel() {
        try {
            if (mmSocket != null) {
                // 没那么多废话，直接把整个蓝牙桥梁（Socket）炸毁。只要 Socket 一关，
                // 上面 receiveData 里那个正在死等的 read 就会立刻报错，从而触发 break 结束掉那个死循环，整个线程彻底死亡，干干净净。
                mmSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭蓝牙套接字失败", e);
        }
    }
}
