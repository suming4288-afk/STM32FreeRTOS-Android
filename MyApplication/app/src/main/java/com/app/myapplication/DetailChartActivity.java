package com.app.myapplication;

import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

// 图表库的工具包
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

/**
 * 详情图表页面 Activity
 * 负责：专门全屏展示某一种数据（如只看温度）的实时跳动曲线
 */
public class DetailChartActivity extends AppCompatActivity {

    private LineChart chart; // 画布
    private String type;     // 记录当前看的是啥数据（温度？湿度？）

    // ======== 核心知识点：静态实例 ========
    // 💡 给新人的比喻：正常情况下，页面和页面之间是互相独立的房间。
    // 把 instance 设为 public static，就像是在这个房间里留了一部“直通座机”。
    // 这样远在另一个房间的 MainActivity，就可以直接拿起电话 (DetailChartActivity.instance.onNewDataReceived)
    // 把最新的传感器数据实时塞进这个页面里。
    public static DetailChartActivity instance = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 【规律一：找“长相”与“灵魂”的连线】
        setContentView(R.layout.activity_detail_chart);

        // 【重要】页面刚出生，赶紧接通那部“直通座机”，告诉外界“我准备好接数据了”
        instance = this;

        // 抓取 XML 里的控件
        chart = findViewById(R.id.detailChart);
        TextView tvTitle = findViewById(R.id.tvDetailTitle);

        // 【规律二的源头追溯：Intent 传值】
        // 记得登录页买车票 (Intent) 的比喻吗？
        // 这里是从 Intent 这张“车票”上，看上一站 (MainActivity) 传过来的备注信息。
        // 如果传来的是 "温度"，我们就知道这面墙要画温度曲线了。
        type = getIntent().getStringExtra("TYPE");
        if (type == null) type = "数据"; // 兜底防错，万一车票上没写，就叫“数据”

        tvTitle.setText(type + " 实时监控"); // 把标题改掉

        initChart(); // 把图表的横纵坐标、网格线先画好（搭好舞台）
    }

    private void initChart() {
        chart.getDescription().setEnabled(false);
        chart.setNoDataText("正在等待数据..."); // 还没数据时屏幕中间显示的字

        LineData data = new LineData();
        chart.setData(data);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM); // 横坐标放在底部
        xAxis.setDrawGridLines(false); // 关掉背景的网格线，看起来更清爽
        chart.getAxisRight().setEnabled(false); // 关掉右边的纵坐标，只留左边
    }

    // 【规律二：数据的“流向”与线程切换】
    // 💡 给新人的重点提示：这个方法是给 MainActivity 调用的！
    // 蓝牙子线程（后厨）在 MainActivity 收到数据后，会顺着 instance 这根线把数字传到这里。
    public void onNewDataReceived(final float value) {

        // ⚠️ 极其重要的概念：runOnUiThread
        // 数据虽然是从别人那里传过来了，但传递数据的那个家伙可能是一个“后台子线程”。
        // 安卓死规定：只有“前台服务员”（UI主线程）才能动界面上的东西（画图表）。
        // 所以必须用 runOnUiThread 把任务交给前台服务员，否则 App 会立刻崩溃闪退！
        runOnUiThread(() -> {
            try {
                if (chart == null) return;
                LineData data = chart.getData();
                if (data == null) return;

                // 找到图表里画线的那支“笔” (LineDataSet)
                ILineDataSet set = data.getDataSetByIndex(0);
                if (set == null) {
                    set = createSet(); // 如果没有笔，就去下面造一支对应颜色的笔
                    data.addDataSet(set);
                }

                // 【规律四：节奏感】 - 实时滚动的画面就在这里产生
                // 把刚收到的数值 (value)，变成一个小圆点 (Entry)，画到折线的末尾
                data.addEntry(new Entry(set.getEntryCount(), value), 0);

                // 通知图表：“数据更新啦，赶紧重画！”
                data.notifyDataChanged();
                chart.notifyDataSetChanged();

                // 设置一屏最多显示 50 个点
                chart.setVisibleXRangeMaximum(50);
                // 随着新点不断加入，图表自动向左平移，永远显示最新的一截
                chart.moveViewToX(data.getEntryCount());

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // 【规律三：条件逻辑】 - 根据不同的数据类型，换不同颜色的画笔
    private LineDataSet createSet() {
        LineDataSet set = new LineDataSet(null, type);
        int color = Color.BLUE; // 默认蓝色

        // 如果车票上写的是温度，就换红笔；湿度换绿笔...
        if (type.contains("温度")) color = Color.RED;
        else if (type.contains("湿度")) color = Color.GREEN;
        else if (type.contains("光强")) color = Color.YELLOW;

        set.setColor(color);
        set.setLineWidth(3f); // 线条画粗一点：3像素
        set.setDrawCircles(false); // 不画转折点的小圆圈
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER); // 画平滑的波浪线，而不是直来直去的折线
        set.setDrawValues(false); // 折线上不密密麻麻地写具体数字
        return set;
    }

    // 生命周期 - 页面销毁时
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 【重要扫尾工作】
        // 页面被用户按返回键关掉了，咱们得把“直通座机”的电话线拔掉 (设为 null)。
        // 否则 MainActivity 还会傻傻地往一个已经不存在的页面里塞数据，会导致可怕的“内存泄漏”！
        instance = null;
    }
}
