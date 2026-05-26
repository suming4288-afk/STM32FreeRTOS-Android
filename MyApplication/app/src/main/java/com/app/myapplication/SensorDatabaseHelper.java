package com.app.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

// 这是 MPAndroidChart 图表库的点，用来代表折线图上的一个小圆点
import com.github.mikephil.charting.data.Entry;

import java.util.ArrayList;
import java.util.List;

/**
 * 传感器数据库帮助类 (专门负责记录高频传感器数据的“历史档案管理员”)
 * 负责：建表、按 5 秒节奏存入最新数据、自动踢掉旧数据、提供画图表用的历史数据
 */
public class SensorDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "SensorHistory.db"; // 档案室的名字
    private static final int DB_VERSION = 2; // 版本号（如果你以后想加个气压传感器，改表结构时就要升到 3）
    private static final String TABLE_NAME = "sensor_data"; // 货架名字

    // 【新增的核心逻辑】定义最大保留条数。这就叫“滑动窗口”模式，只留最近的 6 条，避免数据库无限膨胀
    private static final int MAX_RECORDS = 60;

    public SensorDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    // 【规律四：持久化的基础】
    @Override
    public void onCreate(SQLiteDatabase db) {
        // 建表语句。注意最后的 timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
        // 这非常智能：只要你存入温湿度，系统会自动把当前的时间（比如 2026-04-16 10:00:00）盖个戳印在上面
        String createTable = "CREATE TABLE " + TABLE_NAME + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "temperature REAL, " + // REAL 代表存的是带小数点的浮点数
                "humi REAL, " +
                "light REAL, " +
                "dist REAL, " +
                "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP)";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    /**
     * 【规律四与自动化逻辑的结合】
     * 插入数据并自动清理，只保留最新的 60 条
     * 这个方法就是被 MainActivity 里那个 5秒一次的闹钟 (Handler) 频繁调用的
     */
    public void insertAveragedData(float temp, float humi, float light, float dist) {
        SQLiteDatabase db = this.getWritableDatabase();

        // ======== 核心知识点：事务 (Transaction) ========
        //
        // 💡 告诉新人：事务就像是“连招”。这里有两步：1.存新数据 2.删老数据。
        // 如果存了新数据，但删老数据时突然手机没电关机了，数据库就乱套了。
        // beginTransaction 保证了这套连招“要么全部成功，要么全部撤销当作没发生”，绝不拖泥带水。
        db.beginTransaction();
        try {
            // 连招 1. 正常执行插入逻辑（打包进纸箱 ContentValues 放进仓库）
            ContentValues values = new ContentValues();
            values.put("temperature", temp);
            values.put("humi", humi);
            values.put("light", light);
            values.put("dist", dist);
            db.insert(TABLE_NAME, null, values);

            // 连招 2. 自动化清理逻辑（残酷的淘汰制）
            // 这句 SQL 的意思是：把那些 ID 排名进不了前 50（倒序排）的老家伙们，统统删掉！
            // 这样货架上永远只有最新鲜的 50 条数据。
            String deleteSql = "DELETE FROM " + TABLE_NAME +
                    " WHERE id NOT IN (SELECT id FROM " + TABLE_NAME +
                    " ORDER BY id DESC LIMIT " + MAX_RECORDS + ")";
            db.execSQL(deleteSql);

            // 连招顺利打完，盖个章确认成功！
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e("DB_ERROR", "插入或清理数据失败: " + e.getMessage());
        } finally {
            // 无论刚才成功还是报错，最后都要结束这套连招，并关好仓库大门
            db.endTransaction();
            db.close();
        }
    }

    /**
     * 【规律二：数据的“流向” —— 从仓库走向图表】
     * 获取指定传感器类型的历史数据，专门用来喂给 MPAndroidChart 画折线图
     */
    public List<Entry> getHistoryEntries(String type, int limit) {
        List<Entry> entries = new ArrayList<>(); // 准备一个空篮子，装画图用的点
        SQLiteDatabase db = this.getReadableDatabase(); // 拿只读钥匙

        // 判断 MainActivity 要看哪种图，就去拿对应的哪一列数据
        String column = "";
        switch (type) {
            case "温度": column = "temperature"; break;
            case "湿度": column = "humi"; break;
            case "光强": column = "light"; break;
            case "距离": column = "dist"; break;
        }

        if (column.isEmpty()) return entries; // 如果名字不对，直接返回空篮子

        // ======== 核心知识点：嵌套倒装 SQL ========
        // 💡 告诉新人：这段 SQL 看似复杂，其实非常巧妙。
        // 如果直接拿最新数据，画出来的图是“时间倒流”的（最新点在最左边）。
        // 所以我们先在括号里 (SELECT ... ORDER BY id DESC) 把最新的几十条找出来；
        // 然后再套一层 (SELECT ... ORDER BY id ASC)，把找出来的这些数据重新按从小到大的顺序排好。
        // 这样画出来的折线图，时间线才是正常的从左往右走的！
        String query = "SELECT " + column + " FROM (" +
                "SELECT id, " + column + " FROM " + TABLE_NAME +
                " ORDER BY id DESC LIMIT " + limit +
                ") ORDER BY id ASC";

        // 拿着放大镜 (Cursor) 去找
        Cursor cursor = db.rawQuery(query, null);
        int index = 0; // 这个 index 代表横坐标的第 0, 1, 2, 3 个点

        if (cursor.moveToFirst()) { // 如果找到了第一条
            do {
                // 把放大镜看到的值拿出来 (getFloat(0) 代表拿查询结果的第一列)
                float val = cursor.getFloat(0);
                // 组装成一个 Entry 点 (横坐标, 纵坐标)，扔进篮子里
                entries.add(new Entry(index++, val));
            } while (cursor.moveToNext()); // 循环一直往后看，直到看完所有数据
        }

        cursor.close(); // 收起放大镜
        db.close();     // 关门
        return entries; // 把装满点坐标的篮子，交给前台的 MainActivity 去画图
    }
}
