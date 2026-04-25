package com.github.tvbox.osc.util;

import android.os.Process;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.promeg.pinyinhelper.Pinyin;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.bean.VodInfo;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 基于真实片名缓存的拼音首字母候选器。
 * 启动时从本地历史与收藏预热，运行期持续把真实片名回灌进缓存。
 */
public class DynamicPinyinCandidateUtil {

    public static final String KEY_MOVIE_NAMES_CACHE = "movie_names_cache";
    public static final String KEY_MOVIE_NAMES_TIME = "movie_names_time";
    public static final String KEY_FULL_CRAWL_DONE = "movie_names_full_crawl_done_v1";
    public static final String KEY_FULL_CRAWL_RUNNING = "movie_names_full_crawl_running";
    public static final String KEY_FULL_CRAWL_START_TIME = "movie_names_full_crawl_start_time";
    public static final String KEY_FULL_CRAWL_END_TIME = "movie_names_full_crawl_end_time";
    public static final String KEY_FULL_CRAWL_NEW_COUNT = "movie_names_full_crawl_new_count";
    public static final String KEY_FULL_CRAWL_SOURCE_COUNT = "movie_names_full_crawl_source_count";
    public static final String KEY_FULL_CRAWL_HEARTBEAT = "movie_names_full_crawl_heartbeat";
    public static final String KEY_FULL_CRAWL_OWNER_PID = "movie_names_full_crawl_owner_pid";
    public static final String KEY_FULL_CRAWL_DONE_PAGES = "movie_names_full_crawl_done_pages";
    public static final String KEY_FULL_CRAWL_DONE_CATEGORIES = "movie_names_full_crawl_done_categories";
    public static final long CACHE_VALIDITY_MS = 7 * 24 * 60 * 60 * 1000L; // 7天
    private static final int MAX_CACHE_SIZE = 50000;
    private static final int MAX_CATEGORY_PAGES = 20;
    private static final int MAX_CATEGORIES_PER_SOURCE = 50;
    private static final int MAX_CANDIDATES = 30;
    private static final int MIN_EFFECTIVE_CRAWL_NEW_NAMES = 200;
    private static final int MIN_REASONABLE_CACHE_SIZE = 500;
    private static final long CRAWL_HEARTBEAT_STALE_MS = 2 * 60 * 1000L;
    private static final long CRAWL_MAX_RUNNING_MS = 30 * 60 * 1000L;
    private static final ExecutorService WARMUP_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final OkHttpClient HTTP_CLIENT = buildHttpClient();

    public static void warmUpCacheAsync() {
        WARMUP_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                recoverStuckCrawlStateIfNeeded();
                updateCandidatesCache();
                runFirstFullCrawlIfNeeded();
            }
        });
    }

    private static void recoverStuckCrawlStateIfNeeded() {
        boolean running = Hawk.get(KEY_FULL_CRAWL_RUNNING, false);
        if (!running) {
            return;
        }
        int ownerPid = Hawk.get(KEY_FULL_CRAWL_OWNER_PID, 0);
        int currentPid = Process.myPid();
        long start = Hawk.get(KEY_FULL_CRAWL_START_TIME, 0L);
        long heartbeat = Hawk.get(KEY_FULL_CRAWL_HEARTBEAT, 0L);
        long now = System.currentTimeMillis();
        boolean processChanged = ownerPid > 0 && ownerPid != currentPid;
        boolean noStartTime = start <= 0;
        boolean maxTimeout = start > 0 && now - start > CRAWL_MAX_RUNNING_MS;
        boolean staleHeartbeat = heartbeat > 0 && now - heartbeat > CRAWL_HEARTBEAT_STALE_MS;
        boolean noHeartbeat = heartbeat <= 0 && start > 0 && now - start > CRAWL_HEARTBEAT_STALE_MS;
        if (processChanged || noStartTime || maxTimeout || staleHeartbeat || noHeartbeat) {
            Hawk.put(KEY_FULL_CRAWL_RUNNING, false);
            Hawk.put(KEY_FULL_CRAWL_DONE, false);
            Hawk.put(KEY_FULL_CRAWL_END_TIME, now);
            Hawk.put(KEY_FULL_CRAWL_OWNER_PID, 0);
            LOG.e("movie-name crawl state recovered, processChanged=" + processChanged + ", ownerPid=" + ownerPid + ", currentPid=" + currentPid + ", noStartTime=" + noStartTime + ", maxTimeout=" + maxTimeout + ", staleHeartbeat=" + staleHeartbeat + ", noHeartbeat=" + noHeartbeat + ", start=" + start + ", heartbeat=" + heartbeat + ", now=" + now);
        }
    }

    private static void touchCrawlHeartbeat() {
        Hawk.put(KEY_FULL_CRAWL_HEARTBEAT, System.currentTimeMillis());
    }

    private static OkHttpClient buildHttpClient() {
        OkHttpClient base = OkGo.getInstance().getOkHttpClient();
        if (base != null) {
            return base.newBuilder()
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 启动或过期后重建片名缓存。
     */
    public static void updateCandidatesCache() {
        long lastTime = Hawk.get(KEY_MOVIE_NAMES_TIME, 0L);
        long now = System.currentTimeMillis();
        if (now - lastTime <= CACHE_VALIDITY_MS) {
            return;
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            List<VodInfo> records = RoomDataManger.getAllVodRecord(5000);
            for (VodInfo info : records) {
                if (info != null && !TextUtils.isEmpty(info.name)) {
                    names.add(info.name.trim());
                }
                if (names.size() >= MAX_CACHE_SIZE) {
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (names.size() < MAX_CACHE_SIZE) {
            try {
                List<VodCollect> collects = RoomDataManger.getAllVodCollect();
                for (VodCollect collect : collects) {
                    if (collect != null && !TextUtils.isEmpty(collect.name)) {
                        names.add(collect.name.trim());
                    }
                    if (names.size() >= MAX_CACHE_SIZE) {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        Hawk.put(KEY_MOVIE_NAMES_CACHE, new ArrayList<>(names));
        Hawk.put(KEY_MOVIE_NAMES_TIME, now);
    }

    private static void runFirstFullCrawlIfNeeded() {
        boolean done = Hawk.get(KEY_FULL_CRAWL_DONE, false);
        boolean running = Hawk.get(KEY_FULL_CRAWL_RUNNING, false);
        int cachedSize = Hawk.get(KEY_MOVIE_NAMES_CACHE, new ArrayList<String>()).size();

        if (done && cachedSize >= MIN_REASONABLE_CACHE_SIZE) {
            LOG.i("movie-name crawl skip: already done, cacheSize=" + cachedSize);
            return;
        }

        if (done && cachedSize < MIN_REASONABLE_CACHE_SIZE) {
            LOG.e("movie-name crawl done flag reset: cache too small, cacheSize=" + cachedSize);
            Hawk.put(KEY_FULL_CRAWL_DONE, false);
            done = false;
        }

        if (running) {
            LOG.i("movie-name crawl skip: already running");
            return;
        }

        List<SourceBean> sources = ApiConfig.get().getSourceBeanList();
        if (sources == null || sources.isEmpty()) {
            // 配置尚未准备好，下一次 warmUp 再尝试。
            LOG.e("movie-name crawl skipped: source list is empty");
            return;
        }

        Hawk.put(KEY_FULL_CRAWL_RUNNING, true);
        Hawk.put(KEY_FULL_CRAWL_START_TIME, System.currentTimeMillis());
        Hawk.put(KEY_FULL_CRAWL_END_TIME, 0L);
        Hawk.put(KEY_FULL_CRAWL_NEW_COUNT, 0);
        Hawk.put(KEY_FULL_CRAWL_SOURCE_COUNT, 0);
        Hawk.put(KEY_FULL_CRAWL_OWNER_PID, Process.myPid());
        touchCrawlHeartbeat();
        LOG.i("movie-name crawl started");
        try {
            LinkedHashSet<String> names = new LinkedHashSet<>(getMovieNamesCache());
            Set<String> donePages = getDonePages();
            Set<String> doneCategories = getDoneCategories();
            int startSize = names.size();
            int sourceCount = 0;
            LOG.i("movie-name crawl source total=" + sources.size() + ", initialCache=" + startSize);
            for (SourceBean source : sources) {
                if (source == null) {
                    continue;
                }
                sourceCount++;
                touchCrawlHeartbeat();
                long sourceStart = System.currentTimeMillis();
                int beforeSource = names.size();
                LOG.i("movie-name crawl source start " + sourceCount + "/" + sources.size() + ", key=" + source.getKey() + ", type=" + source.getType());
                crawlSourceMovieNames(source, names, donePages, doneCategories);
                touchCrawlHeartbeat();
                int sourceAdded = Math.max(0, names.size() - beforeSource);
                long sourceCost = System.currentTimeMillis() - sourceStart;
                LOG.i("movie-name crawl source done " + sourceCount + "/" + sources.size() + ", key=" + source.getKey() + ", added=" + sourceAdded + ", total=" + names.size() + ", costMs=" + sourceCost);

                if (names.size() >= MAX_CACHE_SIZE) {
                    LOG.i("movie-name crawl reached MAX_CACHE_SIZE=" + MAX_CACHE_SIZE + ", stop early");
                    break;
                }
            }

            int newCount = Math.max(0, names.size() - startSize);
            persistCacheSnapshot(names, true);
            Hawk.put(KEY_FULL_CRAWL_NEW_COUNT, newCount);
            Hawk.put(KEY_FULL_CRAWL_SOURCE_COUNT, sourceCount);
            Hawk.put(KEY_FULL_CRAWL_END_TIME, System.currentTimeMillis());

            // 只有抓取结果达到有效规模才标记完成，避免空抓取后永不重试。
            boolean effectiveDone = sourceCount > 0 && (newCount >= MIN_EFFECTIVE_CRAWL_NEW_NAMES || names.size() >= MAX_CACHE_SIZE);
            Hawk.put(KEY_FULL_CRAWL_DONE, effectiveDone);
            LOG.i("movie-name crawl finished, sources=" + sourceCount + ", new=" + newCount + ", total=" + names.size() + ", done=" + effectiveDone);
        } catch (Throwable th) {
            th.printStackTrace();
            Hawk.put(KEY_FULL_CRAWL_END_TIME, System.currentTimeMillis());
        } finally {
            Hawk.put(KEY_FULL_CRAWL_RUNNING, false);
            Hawk.put(KEY_FULL_CRAWL_OWNER_PID, 0);
            touchCrawlHeartbeat();
        }
    }

    private static void persistCacheSnapshot(Set<String> names, boolean finished) {
        Hawk.put(KEY_MOVIE_NAMES_CACHE, new ArrayList<>(names));
        Hawk.put(KEY_MOVIE_NAMES_TIME, System.currentTimeMillis());
        if (!finished) {
            LOG.i("movie-name crawl snapshot updated, currentTotal=" + names.size());
        }
    }

    private static String buildCategoryKey(SourceBean source, String categoryId) {
        return source.getKey() + "|" + source.getType() + "|" + categoryId;
    }

    private static String buildPageKey(SourceBean source, String categoryId, int page) {
        return source.getKey() + "|" + source.getType() + "|" + categoryId + "|" + page;
    }

    private static Set<String> getDonePages() {
        List<String> pages = Hawk.get(KEY_FULL_CRAWL_DONE_PAGES, new ArrayList<String>());
        return new LinkedHashSet<>(pages);
    }

    private static Set<String> getDoneCategories() {
        List<String> categories = Hawk.get(KEY_FULL_CRAWL_DONE_CATEGORIES, new ArrayList<String>());
        return new LinkedHashSet<>(categories);
    }

    private static void markPageDone(String pageKey, Set<String> donePages) {
        if (donePages.add(pageKey)) {
            Hawk.put(KEY_FULL_CRAWL_DONE_PAGES, new ArrayList<>(donePages));
        }
    }

    private static void markCategoryDone(String categoryKey, Set<String> doneCategories) {
        if (doneCategories.add(categoryKey)) {
            Hawk.put(KEY_FULL_CRAWL_DONE_CATEGORIES, new ArrayList<>(doneCategories));
        }
    }

    private static void crawlSourceMovieNames(SourceBean source, Set<String> names, Set<String> donePages, Set<String> doneCategories) {
        int type = source.getType();
        if (type == 3) {
            crawlSpiderSource(source, names, donePages, doneCategories);
            return;
        }
        if (type == 0 || type == 1) {
            crawlApiSource(source, names, donePages, doneCategories);
            return;
        }
        LOG.e("movie-name crawl unsupported source type, key=" + source.getKey() + ", type=" + type);
    }

    private static void crawlSpiderSource(SourceBean source, Set<String> names, Set<String> donePages, Set<String> doneCategories) {
        try {
            Spider spider = ApiConfig.get().getCSP(source);
            if (spider == null) {
                LOG.e("movie-name crawl spider is null, key=" + source.getKey());
                return;
            }
            String homeJson = spider.homeContent(true);
            addNamesFromJson(homeJson, names);

            List<String> categoryIds = parseCategoryIdsFromJson(homeJson);
            LOG.i("movie-name crawl spider categories, key=" + source.getKey() + ", count=" + categoryIds.size());
            int categoryCount = 0;
            for (String categoryId : categoryIds) {
                if (TextUtils.isEmpty(categoryId)) {
                    continue;
                }
                categoryCount++;
                if (categoryCount > MAX_CATEGORIES_PER_SOURCE) {
                    LOG.i("movie-name crawl spider category limit reached, key=" + source.getKey() + ", limit=" + MAX_CATEGORIES_PER_SOURCE);
                    break;
                }
                String categoryKey = buildCategoryKey(source, categoryId);
                if (doneCategories.contains(categoryKey)) {
                    LOG.i("movie-name crawl spider category skip(done), key=" + source.getKey() + ", category=" + categoryId);
                    continue;
                }
                int beforeCategory = names.size();
                for (int page = 1; page <= MAX_CATEGORY_PAGES; page++) {
                    String pageKey = buildPageKey(source, categoryId, page);
                    if (donePages.contains(pageKey)) {
                        if (page == 1 || page % 5 == 0) {
                            LOG.i("movie-name crawl spider page skip(done), key=" + source.getKey() + ", category=" + categoryId + ", page=" + page);
                        }
                        continue;
                    }
                    touchCrawlHeartbeat();
                    String pageJson = spider.categoryContent(categoryId, String.valueOf(page), true, new HashMap<String, String>());
                    int before = names.size();
                    AbsXml data = parseJsonToAbsXml(pageJson);
                    addNamesFromAbsXml(data, names);
                    markPageDone(pageKey, donePages);
                    persistCacheSnapshot(names, false);
                    if (page == 1 || page % 5 == 0) {
                        int pageAdded = Math.max(0, names.size() - before);
                        LOG.i("movie-name crawl spider page, key=" + source.getKey() + ", category=" + categoryId + ", page=" + page + ", pageAdded=" + pageAdded + ", total=" + names.size());
                    }
                    if (shouldStopPaging(data, before, names.size(), page)) {
                        markCategoryDone(categoryKey, doneCategories);
                        int categoryAdded = Math.max(0, names.size() - beforeCategory);
                        LOG.i("movie-name crawl spider category done, key=" + source.getKey() + ", category=" + categoryId + ", page=" + page + ", categoryAdded=" + categoryAdded + ", total=" + names.size());
                        break;
                    }
                }
                if (names.size() >= MAX_CACHE_SIZE) {
                    break;
                }
            }
        } catch (Throwable th) {
            LOG.e("movie-name crawl spider error, key=" + source.getKey() + ", msg=" + th.getMessage());
            th.printStackTrace();
        }
    }

    private static void crawlApiSource(SourceBean source, Set<String> names, Set<String> donePages, Set<String> doneCategories) {
        try {
            String homeRaw = httpGet(source.getApi(), null);
            if (TextUtils.isEmpty(homeRaw)) {
                LOG.e("movie-name crawl api home empty, key=" + source.getKey() + ", api=" + source.getApi());
                return;
            }

            if (source.getType() == 1) {
                addNamesFromJson(homeRaw, names);
                List<String> categoryIds = parseCategoryIdsFromJson(homeRaw);
                LOG.i("movie-name crawl api(json) categories, key=" + source.getKey() + ", count=" + categoryIds.size());
                crawlApiCategoryPages(source, categoryIds, names, donePages, doneCategories);
            } else {
                addNamesFromXml(homeRaw, names);
                List<String> categoryIds = parseCategoryIdsFromXml(homeRaw);
                LOG.i("movie-name crawl api(xml) categories, key=" + source.getKey() + ", count=" + categoryIds.size());
                crawlApiCategoryPages(source, categoryIds, names, donePages, doneCategories);
            }
        } catch (Throwable th) {
            LOG.e("movie-name crawl api error, key=" + source.getKey() + ", msg=" + th.getMessage());
            th.printStackTrace();
        }
    }

    private static void crawlApiCategoryPages(SourceBean source, List<String> categoryIds, Set<String> names, Set<String> donePages, Set<String> doneCategories) {
        int categoryCount = 0;
        for (String categoryId : categoryIds) {
            if (TextUtils.isEmpty(categoryId)) {
                continue;
            }
            categoryCount++;
            if (categoryCount > MAX_CATEGORIES_PER_SOURCE) {
                LOG.i("movie-name crawl api category limit reached, key=" + source.getKey() + ", limit=" + MAX_CATEGORIES_PER_SOURCE);
                break;
            }

            String categoryKey = buildCategoryKey(source, categoryId);
            if (doneCategories.contains(categoryKey)) {
                LOG.i("movie-name crawl api category skip(done), key=" + source.getKey() + ", category=" + categoryId);
                continue;
            }

            int beforeCategory = names.size();
            for (int page = 1; page <= MAX_CATEGORY_PAGES; page++) {
                String pageKey = buildPageKey(source, categoryId, page);
                if (donePages.contains(pageKey)) {
                    if (page == 1 || page % 5 == 0) {
                        LOG.i("movie-name crawl api page skip(done), key=" + source.getKey() + ", category=" + categoryId + ", page=" + page);
                    }
                    continue;
                }
                touchCrawlHeartbeat();
                Map<String, String> params = new HashMap<>();
                params.put("ac", source.getType() == 0 ? "videolist" : "detail");
                params.put("t", categoryId);
                params.put("pg", String.valueOf(page));

                String body = httpGet(source.getApi(), params);
                int before = names.size();
                if (source.getType() == 1) {
                    AbsXml data = parseJsonToAbsXml(body);
                    addNamesFromAbsXml(data, names);
                    markPageDone(pageKey, donePages);
                    persistCacheSnapshot(names, false);
                    if (page == 1 || page % 5 == 0) {
                        int pageAdded = Math.max(0, names.size() - before);
                        LOG.i("movie-name crawl api page, key=" + source.getKey() + ", category=" + categoryId + ", page=" + page + ", pageAdded=" + pageAdded + ", total=" + names.size());
                    }
                    if (shouldStopPaging(data, before, names.size(), page)) {
                        markCategoryDone(categoryKey, doneCategories);
                        int categoryAdded = Math.max(0, names.size() - beforeCategory);
                        LOG.i("movie-name crawl api category done, key=" + source.getKey() + ", category=" + categoryId + ", page=" + page + ", categoryAdded=" + categoryAdded + ", total=" + names.size());
                        break;
                    }
                } else {
                    AbsXml data = parseXmlToAbsXml(body);
                    addNamesFromAbsXml(data, names);
                    markPageDone(pageKey, donePages);
                    persistCacheSnapshot(names, false);
                    if (page == 1 || page % 5 == 0) {
                        int pageAdded = Math.max(0, names.size() - before);
                        LOG.i("movie-name crawl api page, key=" + source.getKey() + ", category=" + categoryId + ", page=" + page + ", pageAdded=" + pageAdded + ", total=" + names.size());
                    }
                    if (shouldStopPaging(data, before, names.size(), page)) {
                        markCategoryDone(categoryKey, doneCategories);
                        int categoryAdded = Math.max(0, names.size() - beforeCategory);
                        LOG.i("movie-name crawl api category done, key=" + source.getKey() + ", category=" + categoryId + ", page=" + page + ", categoryAdded=" + categoryAdded + ", total=" + names.size());
                        break;
                    }
                }
            }

            if (names.size() >= MAX_CACHE_SIZE) {
                break;
            }
        }
    }

    private static boolean shouldStopPaging(AbsXml data, int beforeSize, int afterSize, int page) {
        if (data == null || data.movie == null || data.movie.videoList == null || data.movie.videoList.isEmpty()) {
            return true;
        }
        if (afterSize == beforeSize) {
            return true;
        }
        return data.movie.pagecount > 0 && page >= data.movie.pagecount;
    }

    private static void addNamesFromJson(String json, Set<String> names) {
        AbsXml data = parseJsonToAbsXml(json);
        addNamesFromAbsXml(data, names);
    }

    private static void addNamesFromXml(String xml, Set<String> names) {
        AbsXml data = parseXmlToAbsXml(xml);
        addNamesFromAbsXml(data, names);
    }

    private static void addNamesFromAbsXml(AbsXml data, Set<String> names) {
        if (data == null || data.movie == null || data.movie.videoList == null) {
            return;
        }
        for (Movie.Video video : data.movie.videoList) {
            if (video != null && !TextUtils.isEmpty(video.name)) {
                names.add(video.name.trim());
                if (names.size() >= MAX_CACHE_SIZE) {
                    return;
                }
            }
        }
    }

    private static List<String> parseCategoryIdsFromJson(String json) {
        List<String> ids = new ArrayList<>();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = new Gson().fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            if (sortJson != null && sortJson.classes != null) {
                for (AbsSortJson.AbsJsonClass cls : sortJson.classes) {
                    if (cls != null && !TextUtils.isEmpty(cls.type_id)) {
                        ids.add(cls.type_id);
                    }
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return ids;
    }

    private static List<String> parseCategoryIdsFromXml(String xml) {
        List<String> ids = new ArrayList<>();
        try {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            AbsSortXml sortXml = (AbsSortXml) xstream.fromXML(xml);
            if (sortXml != null && sortXml.classes != null && sortXml.classes.sortList != null) {
                for (MovieSort.SortData sortData : sortXml.classes.sortList) {
                    if (sortData != null && !TextUtils.isEmpty(sortData.id)) {
                        ids.add(sortData.id);
                    }
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return ids;
    }

    private static AbsXml parseJsonToAbsXml(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            AbsJson absJson = new Gson().fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            if (absJson == null) {
                return null;
            }
            return absJson.toAbsXml();
        } catch (Throwable th) {
            return null;
        }
    }

    private static AbsXml parseXmlToAbsXml(String xml) {
        if (TextUtils.isEmpty(xml)) {
            return null;
        }
        try {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            return (AbsXml) xstream.fromXML(xml);
        } catch (Throwable th) {
            return null;
        }
    }

    private static String httpGet(String url, Map<String, String> params) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed == null) {
                return "";
            }
            HttpUrl.Builder builder = parsed.newBuilder();
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                        builder.addQueryParameter(entry.getKey(), entry.getValue());
                    }
                }
            }

            Request request = new Request.Builder()
                    .url(builder.build())
                    .get()
                    .build();

            Response response = HTTP_CLIENT.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return "";
            }
            return response.body().string();
        } catch (Throwable th) {
            return "";
        }
    }

    /**
     * 添加单个片名到缓存。
     */
    public static void addMovieName(String movieName) {
        if (TextUtils.isEmpty(movieName)) {
            return;
        }
        List<String> movieNames = getMovieNamesCache();
        String normalized = movieName.trim();
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        if (movieNames.contains(normalized)) {
            return;
        }
        movieNames.add(0, normalized);
        if (movieNames.size() > MAX_CACHE_SIZE) {
            movieNames = new ArrayList<>(movieNames.subList(0, MAX_CACHE_SIZE));
        }
        Hawk.put(KEY_MOVIE_NAMES_CACHE, movieNames);
    }

    /**
     * 批量添加片名到缓存。
     */
    public static void addMovieNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        List<String> movieNames = getMovieNamesCache();
        LinkedHashSet<String> merged = new LinkedHashSet<>(movieNames);
        for (String name : names) {
            if (!TextUtils.isEmpty(name)) {
                merged.add(name.trim());
            }
            if (merged.size() >= MAX_CACHE_SIZE) {
                break;
            }
        }
        Hawk.put(KEY_MOVIE_NAMES_CACHE, new ArrayList<>(merged));
    }

    /**
     * 从片名缓存里按拼音首字母匹配候选。
     */
    public static List<String> getCandidates(String pinyinInitials) {
        List<String> candidates = new ArrayList<>();
        if (!isPinyinInitials(pinyinInitials)) {
            return candidates;
        }

        String keyword = pinyinInitials.toLowerCase();
        List<String> movieNames = getMovieNamesCache();
        for (String name : movieNames) {
            if (TextUtils.isEmpty(name)) {
                continue;
            }
            String initials = buildPinyinInitials(name);
            if (!TextUtils.isEmpty(initials) && initials.startsWith(keyword)) {
                candidates.add(name);
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
        }
        return candidates;
    }

    private static List<String> getMovieNamesCache() {
        List<String> names = Hawk.get(KEY_MOVIE_NAMES_CACHE, new ArrayList<String>());
        if (names.isEmpty()) {
            updateCandidatesCache();
            names = Hawk.get(KEY_MOVIE_NAMES_CACHE, new ArrayList<String>());
        }
        return names;
    }

    /**
     * 清除缓存（用于测试或手动刷新）
     */
    public static void clearCache() {
        Hawk.put(KEY_MOVIE_NAMES_CACHE, new ArrayList<String>());
        Hawk.put(KEY_MOVIE_NAMES_TIME, 0L);
        Hawk.put(KEY_FULL_CRAWL_DONE, false);
        Hawk.put(KEY_FULL_CRAWL_RUNNING, false);
        Hawk.put(KEY_FULL_CRAWL_START_TIME, 0L);
        Hawk.put(KEY_FULL_CRAWL_END_TIME, 0L);
        Hawk.put(KEY_FULL_CRAWL_NEW_COUNT, 0);
        Hawk.put(KEY_FULL_CRAWL_SOURCE_COUNT, 0);
        Hawk.put(KEY_FULL_CRAWL_HEARTBEAT, 0L);
        Hawk.put(KEY_FULL_CRAWL_OWNER_PID, 0);
        Hawk.put(KEY_FULL_CRAWL_DONE_PAGES, new ArrayList<String>());
        Hawk.put(KEY_FULL_CRAWL_DONE_CATEGORIES, new ArrayList<String>());
    }

    public static String getCrawlStatusSummary() {
        boolean running = Hawk.get(KEY_FULL_CRAWL_RUNNING, false);
        boolean done = Hawk.get(KEY_FULL_CRAWL_DONE, false);
        long start = Hawk.get(KEY_FULL_CRAWL_START_TIME, 0L);
        long end = Hawk.get(KEY_FULL_CRAWL_END_TIME, 0L);
        long heartbeat = Hawk.get(KEY_FULL_CRAWL_HEARTBEAT, 0L);
        int ownerPid = Hawk.get(KEY_FULL_CRAWL_OWNER_PID, 0);
        int added = Hawk.get(KEY_FULL_CRAWL_NEW_COUNT, 0);
        int sources = Hawk.get(KEY_FULL_CRAWL_SOURCE_COUNT, 0);
        int total = Hawk.get(KEY_MOVIE_NAMES_CACHE, new ArrayList<String>()).size();
        int donePages = Hawk.get(KEY_FULL_CRAWL_DONE_PAGES, new ArrayList<String>()).size();
        int doneCategories = Hawk.get(KEY_FULL_CRAWL_DONE_CATEGORIES, new ArrayList<String>()).size();
        String reason;
        if (running) {
            reason = "running";
        } else if (done && total >= MIN_REASONABLE_CACHE_SIZE) {
            reason = "done-with-enough-cache";
        } else if (done) {
            reason = "done-but-cache-small";
        } else {
            reason = "not-started-or-retry";
        }
        return "running=" + running + ", done=" + done + ", added=" + added + ", total=" + total + ", sources=" + sources + ", doneCategories=" + doneCategories + ", donePages=" + donePages + ", ownerPid=" + ownerPid + ", start=" + start + ", heartbeat=" + heartbeat + ", end=" + end + ", reason=" + reason;
    }

    private static String buildPinyinInitials(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String one = Pinyin.toPinyin(text.charAt(i));
            if (TextUtils.isEmpty(one)) {
                continue;
            }
            char first = Character.toLowerCase(one.charAt(0));
            if ((first >= 'a' && first <= 'z') || (first >= '0' && first <= '9')) {
                sb.append(first);
            }
        }
        return sb.toString();
    }

    /**
     * 判断输入是否为拼音首字母
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
}
