package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.github.promeg.pinyinhelper.Pinyin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 拼音首字母候选字工具类
 * 根据拼音首字母生成常用中文字候选
 */
public class PinyinCandidateUtil {

    /**
     * 常用中文字库（按拼音首字母分组）
     * 包含常见的电视剧、电影、综艺等内容中出现的中文字
     */
    private static final HashMap<String, String> PINYIN_CANDIDATES = new HashMap<String, String>() {
        {
            // A 开头的常用字
            put("a", "啊爱安艾阿");
            
            // B 开头的常用字
            put("b", "白百本不被比兵别北步包宝本布薄冰帮贝保邦芭蕃");
            
            // C 开头的常用字
            put("c", "超出城长陈成传穿册察车程草策材彩册侧促催次");
            
            // D 开头的常用字
            put("d", "大道的地电冬第对等独度动短当代丹冬段打德地动");
            
            // E 开头的常用字
            put("e", "恩额峨儿俄鹅");
            
            // F 开头的常用字
            put("f", "风法飞凤方放冯夫付父非分复发福费佛法复放非飞");
            
            // G 开头的常用字
            put("g", "光国故公古高感共格概锅怪谷鬼功根给更古官光");
            
            // H 开头的常用字
            put("h", "和话后河回还回好红黑好鹤恨何华航韩河呼化核");
            
            // I 开头的常用字
            put("i", "");
            
            // J 开头的常用字
            put("j", "就交街结近将家九经精究救街军节进洁间技建交精");
            
            // K 开头的常用字
            put("k", "空可口开科款亏苦");
            
            // L 开头的常用字
            put("l", "领路来林冷离流列乐陆老理林来浪雷闹练了雪溪");
            
            // M 开头的常用字
            put("m", "妈马没么们美梦免墨市命美明米面摩末毛满命木莫");
            
            // N 开头的常用字
            put("n", "你那年女能男难南尼牛农娘念农内能您尼卿");
            
            // O 开头的常用字
            put("o", "欧");
            
            // P 开头的常用字
            put("p", "片皮跑破培朋平普普派判匹贫皮被平迫");
            
            // Q 开头的常用字
            put("q", "秦奇且汽琪墙期强情取枪情清区谯奇起群千切");
            
            // R 开头的常用字
            put("r", "人让认认日若如让热人人若认");
            
            // S 开头的常用字
            put("s", "思送山上身手时三森社说是胜水山沙谁寺士散识释");
            
            // T 开头的常用字
            put("t", "天他图天态特探通天讨体通天天他听");
            
            // U 开头的常用字
            put("u", "");
            
            // V 开头的常用字
            put("v", "");
            
            // W 开头的常用字
            put("w", "我文万未王为望万卧微无望玩万完网威文王");
            
            // X 开头的常用字
            put("x", "西想小新悬虚西行香新下鲜戏细险选欠系现行");
            
            // Y 开头的常用字
            put("y", "要因有一月意远由也要言游杨妖野业液游尤云要");
            
            // Z 开头的常用字
            put("z", "则左张在之中自战知最终中主转支者这种阵至中");
        }
    };

    /**
     * 根据拼音首字母获取候选中文字
     *
     * @param pinyinInitials 拼音首字母串，如 "xy" 表示 "西游"
     * @return 候选中文字或词列表
     */
    public static List<String> getCandidates(String pinyinInitials) {
        List<String> candidates = new ArrayList<>();
        
        if (TextUtils.isEmpty(pinyinInitials)) {
            return candidates;
        }
        
        pinyinInitials = pinyinInitials.toLowerCase();
        
        // 如果只有一个首字母，返回对应的字
        if (pinyinInitials.length() == 1) {
            String chars = PINYIN_CANDIDATES.getOrDefault(pinyinInitials, "");
            if (!chars.isEmpty()) {
                for (int i = 0; i < chars.length(); i++) {
                    candidates.add(String.valueOf(chars.charAt(i)));
                }
            }
        } else {
            // 多个首字母时，尝试组合生成候选词
            candidates.addAll(generateMultiCharCandidates(pinyinInitials));
        }
        
        return candidates;
    }

    /**
     * 根据多个拼音首字母生成候选词
     * 例如输入 "xj" 可能返回 ["西游", "小姐"]等
     *
     * @param pinyinInitials 多个拼音首字母
     * @return 候选词列表
     */
    private static List<String> generateMultiCharCandidates(String pinyinInitials) {
        List<String> candidates = new ArrayList<>();
        
        if (pinyinInitials.length() < 2) {
            return candidates;
        }
        
        // 简单实现：将首字母按位置逐个查表
        // 例如 "xy" -> 从 "x" 对应的字中取第一个，从 "y" 对应的字中也取第一个
        StringBuilder sb = new StringBuilder();
        boolean canBuild = true;
        
        for (int i = 0; i < pinyinInitials.length(); i++) {
            String initial = String.valueOf(pinyinInitials.charAt(i));
            String chars = PINYIN_CANDIDATES.getOrDefault(initial, "");
            if (chars.isEmpty()) {
                canBuild = false;
                break;
            }
            sb.append(chars.charAt(0));
        }
        
        if (canBuild) {
            candidates.add(sb.toString());
        }
        
        // 也返回每个拼音首字母对应的单个字
        for (int i = 0; i < pinyinInitials.length(); i++) {
            String initial = String.valueOf(pinyinInitials.charAt(i));
            String chars = PINYIN_CANDIDATES.getOrDefault(initial, "");
            if (!chars.isEmpty()) {
                candidates.add(String.valueOf(chars.charAt(0)));
            }
        }
        
        return candidates;
    }

    /**
     * 判断输入是否为拼音首字母序列
     * 
     * @param input 用户输入
     * @return true 表示是拼音首字母序列（全是a-z）
     */
    public static boolean isPinyinInitials(String input) {
        if (TextUtils.isEmpty(input)) {
            return false;
        }
        
        input = input.toLowerCase();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        
        return true;
    }

    /**
     * 尝试从用户输入的拼音首字母转换为最可能的中文字/词
     * 用于在用户按搜索前自动转换
     *
     * @param pinyinInput 拼音首字母序列
     * @return 最合适的中文候选（通常是第一个），若不存在返回原输入
     */
    public static String convertToMostLikelyChinese(String pinyinInput) {
        if (!isPinyinInitials(pinyinInput)) {
            return pinyinInput;
        }
        
        List<String> candidates = getCandidates(pinyinInput);
        if (candidates.isEmpty()) {
            return pinyinInput;
        }
        
        return candidates.get(0);
    }
}
