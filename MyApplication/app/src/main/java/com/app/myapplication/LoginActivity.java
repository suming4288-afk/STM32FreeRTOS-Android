package com.app.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 登录页面 Activity (App 的大门)
 * 负责：展示登录界面、核对用户账号密码、成功后放行跳转到主控制台页面
 */
public class LoginActivity extends AppCompatActivity {

    // 页面的出生点
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 【规律一：找“长相”与“灵魂”的连线】
        // 1. 把 res/layout/activity_login.xml 这个画好的登录界面铺在屏幕上
        setContentView(R.layout.activity_login);

        // 召唤数据库管理员，准备核对账本
        MyDatabaseHelper dbHelper = new MyDatabaseHelper(this);

        // 2. 疯狂抓取界面上的控件（把 XML 里的皮囊抓到 Java 里注入灵魂）
        EditText etUser = findViewById(R.id.etLoginUser); // 抓账号输入框
        EditText etPwd = findViewById(R.id.etLoginPwd);   // 抓密码输入框
        Button btnLogin = findViewById(R.id.btnLogin);    // 抓“登录”大按钮
        TextView tvGoReg = findViewById(R.id.tvGoRegister); // 抓“没有账号？去注册”的那行小字

        // 【规律三：识别“条件触发”的自动化逻辑】
        // 给“登录”大按钮安上点击监听器（触发器）
        btnLogin.setOnClickListener(v -> {

            // 【规律二：寻找数据的“源头”】
            // 每次点击，都要重新去输入框里把用户刚敲进去的字拿出来，并去掉首尾空格
            String user = etUser.getText().toString().trim();
            String pwd = etPwd.getText().toString().trim();

            // 逻辑分支判断开始：

            // 第一步：如果是空的，直接报错拦截
            if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pwd)) {
                Toast.makeText(this, "请输入账号和密码", Toast.LENGTH_SHORT).show();

                // 第二步：拿着非空的账号密码，去问数据库管理员 (dbHelper) 账本里有没有这个人，密码对不对？
                // 【规律四：持久化操作的应用】—— 这里是从数据库读取（查）
            } else if (dbHelper.checkLogin(user, pwd)) {

                // 如果管理员点头 (返回 true)，说明核对成功！
                Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show();

                // ======== 核心知识点：Intent (意图) ========
                // Intent 就像是一张“车票”。
                // new Intent(起点，终点) -> 这里的 this 就是当前登录页，MainActivity.class 就是我们要去的主页面（带滚动、图表和物联网数据的页面）
                // startActivity 就是“检票发车”！

                startActivity(new Intent(this, MainActivity.class));

                // 为什么要 finish()？
                // 发车到了主页面后，我们把背后的登录页面“销毁”掉。
                // 这样用户在主页面按手机自带的“返回键”时，就会直接退回手机桌面，而不是退回到登录页，体验才合理。
                finish();

            } else {
                // 如果管理员摇头 (返回 false)，说明账号或密码对不上
                Toast.makeText(this, "账号或密码错误", Toast.LENGTH_SHORT).show();
            }
        });

        // 另一个触发器：给“去注册”那行字安上点击监听
        tvGoReg.setOnClickListener(v -> {
            // 用户没账号，想去注册。买张车票，从这里 (this) 发车去注册页面 (RegisterActivity)
            // 注意：这里没有写 finish()！因为注册完之后，咱们还希望按返回键能顺理成章地回到这个登录页面来登录。
            startActivity(new Intent(this, RegisterActivity.class));
        });
    }
}
