package com.app.myapplication;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 注册页面 Activity
 * 负责：展示注册界面、收集用户输入的账号密码、校验格式、存入数据库
 */
public class RegisterActivity extends AppCompatActivity {

    // onCreate 就是这个页面的“出生点”，页面一打开就会执行这里的代码
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 【规律一：找“长相”与“灵魂”的连线】
        // 第一步：把 res/layout/activity_register.xml 这个画好的界面贴到手机屏幕上
        setContentView(R.layout.activity_register);

        // 准备好数据库小助手（也就是咱们存温湿度、存用户的那个仓库管理员）
        MyDatabaseHelper dbHelper = new MyDatabaseHelper(this);

        // 第二步：通过 findViewById，把界面上的“输入框”和“按钮”抓到 Java 里来
        // 就像是用线把 XML 里的木偶和 Java 里的操作杆连起来
        EditText etUser = findViewById(R.id.etRegUser); // 抓账号输入框
        EditText etPwd = findViewById(R.id.etRegPwd);   // 抓密码输入框
        Button btnReg = findViewById(R.id.btnDoRegister); // 抓注册按钮

        // 【规律三：识别“条件触发”的自动化逻辑】
        // setOnClickListener 叫做“点击监听器”。
        // 它的意思是：给这个按钮安个警报器，只要用户的手指一戳它，大括号里面的代码就会立刻执行！
        btnReg.setOnClickListener(v -> {

            // 【规律二：数据的“源头”】
            // 当按钮被点击时，去输入框里把用户填写的字“拿”出来。
            // .trim() 的作用是贴心地把用户不小心打在开头或结尾的空格给删掉
            String user = etUser.getText().toString().trim();
            String pwd = etPwd.getText().toString().trim();

            // 下面是一系列的“通关守卫”（条件判断），全是为了防止用户乱填

            // 1. 第一关：非空检查
            // 如果账号或密码有一个是空的
            if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pwd)) {
                // Toast 是安卓里的“吐司”提示，就是那种在屏幕底部弹出来一两秒就消失的小黑条
                Toast.makeText(this, "账号或密码不能为空", Toast.LENGTH_SHORT).show();
                return; // return 就是“原路返回，后面的代码不执行了”，相当于闯关失败
            }

            // 2. 第二关：仅限英文检查 (使用正则表达式)
            // ^[a-zA-Z]+$ 这个像乱码一样的东西叫正则，意思是：必须全是英文字母（大小写都行）
            if (!user.matches("^[a-zA-Z]+$")) {
                Toast.makeText(this, "用户名只能包含英文字母", Toast.LENGTH_SHORT).show();
                return; // 闯关失败
            }

            // [新增] 3. 第三关：密码格式检查 (至少8位纯数字)
            // ^[0-9]{8,}$ 的意思是：必须全是数字，且长度至少要 8 个
            if (!pwd.matches("^[0-9]{8,}$")) {
                Toast.makeText(this, "密码必须至少为8位纯数字", Toast.LENGTH_SHORT).show();
                return; // 闯关失败
            }

            // 【规律四：持久化的节奏】
            // 4. 第四关：唯一性检查 + 数据库插入 (真正干活的地方)
            // 只有前面三关都通过了，才会来到这里。
            // dbHelper.register() 去数据库里查重并试图存入新用户。它会返回一个布尔值（true 或 false）
            if (dbHelper.register(user, pwd)) {
                // 如果返回 true，说明数据库存入成功
                Toast.makeText(this, "注册成功", Toast.LENGTH_SHORT).show();
                finish(); // finish() 的意思是销毁当前页面（注册页），这样就自动退回到上一页（登录页）了
            } else {
                // 如果返回 false，通常是因为仓库管理员 (dbHelper) 发现这个名字已经有人用了
                Toast.makeText(this, "该用户名已被占用，请修改", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
