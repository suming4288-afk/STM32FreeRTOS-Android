package com.app.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 数据库帮助类 (仓库管理员)
 * 负责：建库、建表、往里存账号密码、从里面查账号密码
 * 它是继承自 SQLiteOpenHelper 的，这是安卓官方发给它的“管理员上岗证”
 */
public class MyDatabaseHelper extends SQLiteOpenHelper {

    // 仓库的名字（数据库名）
    private static final String DB_NAME = "UserDB.db";
    // 仓库的版本号（如果以后要加新字段，比如加个“手机号”，就把版本号改成 2）
    private static final int DB_VERSION = 1;
    // 仓库里面的具体货架名（表名）
    private static final String TABLE_NAME = "users";

    // 构造方法：当别人 new MyDatabaseHelper 的时候，系统就会按上面的名字和版本号准备好仓库
    public MyDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    // 【规律四：持久化的基础】 - 仓库第一次建好的时候，要打什么样的货架？
    @Override
    public void onCreate(SQLiteDatabase db) {

        // 这是一句 SQL 语言（数据库通用语）。意思是：
        // CREATE TABLE: 打造一个叫 users 的表（货架）
        // id INTEGER PRIMARY KEY AUTOINCREMENT: 给每个人发一个自增的数字编号，1,2,3...（主键）
        // username TEXT UNIQUE: 账号这一列是文字，而且必须“唯一”(UNIQUE)，数据库底层直接把关，不准重名！
        // password TEXT: 密码这一列也是文字
        db.execSQL("CREATE TABLE " + TABLE_NAME + " (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE, password TEXT)");
    }

    // 当 DB_VERSION 版本号变大的时候，系统会自动调用这个方法升级仓库
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 简单粗暴的升级方式：把旧货架砸了 (DROP TABLE)，重新建一个 (onCreate)
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    /**
     * ======== 以下是管理员的日常工作 ========
     */

    /**
     * 工作1：检查用户名是否已被占用
     */
    public boolean checkUserExists(String username) {
        // 既然只是“看一眼”，不需要修改，就拿一把“只读钥匙” (getReadableDatabase)
        SQLiteDatabase db = this.getReadableDatabase();

        // 【核心知识点：Cursor (游标/放大镜)】
        // 拿着放大镜去 users 货架上找（SELECT * FROM），条件是（WHERE username=?）
        // 问号 ? 是一个占位符，会被后面的 new String[]{username} 里的真实账号替换掉
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME + " WHERE username=?", new String[]{username});

        // 看看放大镜找到了几条结果。如果大于 0，说明这个名字已经存在了
        boolean exists = cursor.getCount() > 0;

        // 随手关门，收起放大镜，释放内存
        cursor.close();
        return exists;
    }

    /**
     * 工作2：注册新用户 (往仓库里放东西)
     */
    public boolean register(String username, String password) {
        // 在真正放入仓库前，管理员先查一下账本，确认名字没重复
        if (checkUserExists(username)) {
            return false; // 用户名已存在，退回 (我们在 RegisterActivity 里就是靠这个 false 弹出提示的)
        }

        // 既然要存新东西，这次必须拿一把“写入钥匙” (getWritableDatabase) 才能开大门
        SQLiteDatabase db = this.getWritableDatabase();

        // 【核心知识点：ContentValues (打包纸箱)】
        // 安卓规定，往数据库存东西不能直接塞，得先装进 ContentValues 这个专用的纸箱里
        ContentValues values = new ContentValues();
        values.put("username", username); // 在纸箱上贴个标签 "username"，装入账号
        values.put("password", password); // 在纸箱上贴个标签 "password"，装入密码

        // 管理员把打包好的纸箱，推入 TABLE_NAME (users) 货架
        // 如果插入成功，系统会返回这个用户的编号 (比如 1, 2, 3)；如果失败，会返回 -1
        long result = db.insert(TABLE_NAME, null, values);

        // 返回最终结果：只要不是 -1，就代表注册成功了
        return result != -1;
    }

    /**
     * 工作3：登录验证 (查账)
     */
    public boolean checkLogin(String username, String password) {
        // 同样是核对，拿“只读钥匙”
        SQLiteDatabase db = this.getReadableDatabase();

        // 拿着放大镜去查：找到账号 = ? 并且 (AND) 密码 = ? 的那一整行
        // 这里的两个问号，分别对应后面数组里的 username 和 password
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME + " WHERE username=? AND password=?", new String[]{username, password});

        // 如果能找到哪怕 1 条数据，就说明账号密码完全对得上！
        boolean exists = cursor.getCount() > 0;

        cursor.close(); // 收起放大镜
        return exists; // 返回 true 给 LoginActivity，那边拿到 true 就会放行跳转页面
    }
}
