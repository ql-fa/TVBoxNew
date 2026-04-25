package com.github.tvbox.osc.base;

import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;

import me.jessyan.autosize.AutoSize;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initParams();
        // OKGo
        OkGoHelper.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);
        // 设置默认值
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2); // 默认为Exo播放器
        }
        if (!Hawk.contains(HawkConfig.HOME_REC)) {
            Hawk.put(HawkConfig.HOME_REC, 1); // 默认为站点推荐
        }
        if (!Hawk.contains(HawkConfig.SEARCH_VIEW)) {
            Hawk.put(HawkConfig.SEARCH_VIEW, 1); // 默认为缩略图
        }
        if (!Hawk.contains(HawkConfig.API_URL)) {
            Hawk.put(HawkConfig.API_URL, "https://gitee.com/ltrader/testv/raw/master/default.json");
        }
    }

    public static App getInstance() {
        return instance;
    }
}