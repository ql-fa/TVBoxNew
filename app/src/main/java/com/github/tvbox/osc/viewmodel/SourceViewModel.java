package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> quickSearchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> playResult;

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        quickSearchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
    }

    public static final ExecutorService spThreadPool = Executors.newSingleThreadExecutor();

    public void getSort(String sourceKey) {
        if (sourceKey == null) {
            sortResult.postValue(null);
            return;
        }
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.homeContent(true);
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(15, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        if (sortJson != null) {
                            AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    sortResult.postValue(sortXml);
                                } else {
                                    getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                        @Override
                                        public void done(List<Movie.Video> videos) {
                                            sortXml.videoList = videos;
                                            sortResult.postValue(sortXml);
                                        }
                                    });
                                }
                            } else {
                                sortResult.postValue(sortXml);
                            }
                        } else {
                            sortResult.postValue(null);
                        }
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            spThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_sort")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsSortXml sortXml = null;
                            if (type == 0) {
                                String xml = response.body();
                                sortXml = sortXml(sortResult, xml);
                            } else if (type == 1) {
                                String json = response.body();
                                sortXml = sortJson(sortResult, json);
                            }
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1 && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                AbsSortXml finalSortXml = sortXml;
                                getHomeRecList(sourceBean, ids, new HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        finalSortXml.videoList = videos;
                                        sortResult.postValue(finalSortXml);
                                    }
                                });
                            } else {
                                sortResult.postValue(sortXml);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            sortResult.postValue(null);
                        }
                    });
        } else {
            sortResult.postValue(null);
        }
    }

    public void getList(MovieSort.SortData sortData, int page) {
        if (sortData == null) {
            LOG.e("getList: sortData is null, skip request");
            listResult.postValue(null);
            return;
        }
        SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
        int type = homeSourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(homeSourceBean);
                        json(listResult, sp.categoryContent(sortData.id, page + "", true, sortData.filterSelect), homeSourceBean.getKey());
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            if (sortData.filterSelect == null) {
                // Some XML parsers may bypass field initializers and leave maps null.
                sortData.filterSelect = new java.util.HashMap<>();
            }
            if (type == 0) {
                String selectedYear = sortData.filterSelect.get("year");
                if (selectedYear != null && !selectedYear.isEmpty()) {
                    requestXmlListWithYearFilter(homeSourceBean, sortData, page, selectedYear);
                    return;
                }
            }

            com.lzy.okgo.request.GetRequest<String> listRequest = OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("t", sortData.id)
                    .params("pg", page);
            appendListFilterParams(listRequest, sortData.filterSelect, false, true);
            listRequest.execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(listResult, xml, homeSourceBean.getKey());
                            } else {
                                String json = response.body();
                                if ("older".equals(sortData.filterSelect.get("year"))) {
                                    json = keepOlderThan2020(json);
                                }
                                json = sortByVodTimeDesc(json);
                                json(listResult, json, homeSourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            LOG.e("getList: onError code=" + response.code() + " msg=" + response.message());
                            listResult.postValue(null);
                        }
                    });
        } else {
            listResult.postValue(null);
        }
    }

    private void appendListFilterParams(com.lzy.okgo.request.GetRequest<String> request,
                                        java.util.HashMap<String, String> filterSelect,
                                        boolean skipYearParam,
                                        boolean skipYearOlder) {
        for (java.util.Map.Entry<String, String> entry : filterSelect.entrySet()) {
            if (skipYearParam && "year".equals(entry.getKey())) {
                continue;
            }
            if (skipYearOlder && "year".equals(entry.getKey()) && "older".equals(entry.getValue())) {
                continue;
            }
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                request.params(entry.getKey(), entry.getValue());
            }
        }
    }

    private void requestXmlListWithYearFilter(SourceBean homeSourceBean,
                                              MovieSort.SortData sortData,
                                              int page,
                                              String selectedYear) {
        requestXmlListWithYearFilter(homeSourceBean, sortData, page, selectedYear, new ArrayList<>(), page, 0, 30);
    }

    private void requestXmlListWithYearFilter(SourceBean homeSourceBean,
                                              MovieSort.SortData sortData,
                                              int page,
                                              String selectedYear,
                                              ArrayList<Movie.Video> collected,
                                              int startPage,
                                              int pagesScanned,
                                              int targetSize) {
        com.lzy.okgo.request.GetRequest<String> listRequest = OkGo.<String>get(homeSourceBean.getApi())
                .tag(homeSourceBean.getApi())
                .params("ac", "videolist")
                .params("t", sortData.id)
                .params("pg", page);
        appendListFilterParams(listRequest, sortData.filterSelect, true, true);
        listRequest.execute(new AbsCallback<String>() {
            @Override
            public String convertResponse(okhttp3.Response response) throws Throwable {
                if (response.body() != null) {
                    return response.body().string();
                } else {
                    throw new IllegalStateException("网络请求错误");
                }
            }

            @Override
            public void onSuccess(Response<String> response) {
                String xmlText = response.body();
                AbsXml absXml = xml(null, xmlText, homeSourceBean.getKey());
                if (absXml == null || absXml.movie == null || absXml.movie.videoList == null) {
                    listResult.postValue(absXml);
                    return;
                }
                filterByYearSelection(absXml, selectedYear);
                int pageCount = absXml.movie.pagecount;
                int currentPage = absXml.movie.page;
                int effectiveTargetSize = targetSize;
                if (absXml.movie.pagesize > 0) {
                    effectiveTargetSize = absXml.movie.pagesize;
                }
                if (!absXml.movie.videoList.isEmpty()) {
                    collected.addAll(absXml.movie.videoList);
                }
                boolean needMore = collected.size() < effectiveTargetSize
                        && currentPage > 0
                        && pageCount > 0
                        && currentPage < pageCount
                        && pagesScanned < 50;
                if (needMore) {
                    requestXmlListWithYearFilter(homeSourceBean, sortData, currentPage + 1, selectedYear, collected, startPage, pagesScanned + 1, effectiveTargetSize);
                } else {
                    absXml.movie.videoList = new ArrayList<>(collected);
                    absXml.movie.page = currentPage > 0 ? currentPage : startPage;
                    listResult.postValue(absXml);
                }
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                LOG.e("requestXmlListWithYearFilter: onError code=" + response.code() + " msg=" + response.message());
                listResult.postValue(null);
            }
        });
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }

    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        if (sourceBean.getType() == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.homeVideoContent();
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(15, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        if (sortJson != null) {
                            AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        } else {
                            callback.done(null);
                        }
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            spThreadPool.execute(waitResponse);
        } else if (sourceBean.getType() == 0 || sourceBean.getType() == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", sourceBean.getType() == 0 ? "videolist" : "detail")
                    .params("ids", TextUtils.join(",", ids))
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsXml absXml;
                            if (sourceBean.getType() == 0) {
                                String xml = response.body();
                                absXml = xml(null, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                absXml = json(null, json, sourceBean.getKey());
                            }
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }

    public void getDetail(String sourceKey, String id) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(sourceBean);
                        List<String> ids = new ArrayList<>();
                        ids.add(id);
                        json(detailResult, sp.detailContent(ids), sourceBean.getKey());
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("ids", id)
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(detailResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(detailResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            detailResult.postValue(null);
                        }
                    });
        } else {
            detailResult.postValue(null);
        }
    }

    public void getSearch(String sourceKey, String wd) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                json(searchResult, sp.searchContent(wd, false), sourceBean.getKey());
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(searchResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(searchResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // searchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                        }
                    });
        } else {
            searchResult.postValue(null);
        }
    }

    public void getQuickSearch(String sourceKey, String wd) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                json(quickSearchResult, sp.searchContent(wd, true), sourceBean.getKey());
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("quick_search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(quickSearchResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(quickSearchResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // quickSearchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
                        }
                    });
        } else {
            quickSearchResult.postValue(null);
        }
    }

    public void getPlay(String sourceKey, String playFlag, String progressKey, String url) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    Spider sp = ApiConfig.get().getCSP(sourceBean);
                    String json = sp.playerContent(playFlag, url, ApiConfig.get().getVipParseFlags());
                    LOG.e("run, json=" + json);
                    try {
                        JSONObject result = new JSONObject(json);
                        result.put("key", url);
                        result.put("proKey", progressKey);
                        if (!result.has("flag"))
                            result.put("flag", playFlag);
                        LOG.e(result.toString());
                        playResult.postValue(result);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        playResult.postValue(null);
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            LOG.e("sourceBean.getType()=0|1");
            JSONObject result = new JSONObject();
            try {
                result.put("key", url);
                String playUrl = sourceBean.getPlayerUrl().trim();
                if (DefaultConfig.isVideoFormat(url) && playUrl.isEmpty()) {
                    result.put("parse", 0);
                    result.put("url", url);
                } else {
                    result.put("parse", 1);
                    result.put("url", url);
                }
                result.put("playUrl", playUrl);
                result.put("flag", playFlag);
                LOG.e(result.toString());
                playResult.postValue(result);
            } catch (Throwable th) {
                th.printStackTrace();
                playResult.postValue(null);
            }
        } else {
            quickSearchResult.postValue(null);
        }
    }

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (JsonElement ele : kv) {
            values.put(ele.getAsJsonObject().get("n").getAsString(), ele.getAsJsonObject().get("v").getAsString());
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    private String keepOlderThan2020(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("list") || !root.get("list").isJsonArray()) {
                return json;
            }
            JsonArray srcList = root.getAsJsonArray("list");
            JsonArray dstList = new JsonArray();
            for (JsonElement item : srcList) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject vod = item.getAsJsonObject();
                if (!vod.has("vod_year")) {
                    continue;
                }
                String yearStr = vod.get("vod_year").getAsString();
                try {
                    int year = Integer.parseInt(yearStr);
                    if (year < 2020) {
                        dstList.add(vod);
                    }
                } catch (Throwable th) {
                    // Ignore non-year values.
                }
            }
            root.add("list", dstList);
            return root.toString();
        } catch (Throwable th) {
            return json;
        }
    }

    private long getVodSortKey(JsonObject vod) {
        try {
            if (vod.has("vod_time_add") && !vod.get("vod_time_add").isJsonNull()) {
                String v = vod.get("vod_time_add").getAsString();
                if (!TextUtils.isEmpty(v)) {
                    return Long.parseLong(v);
                }
            }
        } catch (Throwable th) {
            // Ignore parse errors and fallback to vod_time.
        }
        try {
            if (vod.has("vod_time") && !vod.get("vod_time").isJsonNull()) {
                String time = vod.get("vod_time").getAsString();
                if (!TextUtils.isEmpty(time)) {
                    String digits = time.replaceAll("[^0-9]", "");
                    if (!TextUtils.isEmpty(digits)) {
                        return Long.parseLong(digits);
                    }
                }
            }
        } catch (Throwable th) {
            // Ignore parse errors and use default key.
        }
        return 0L;
    }

    private String sortByVodTimeDesc(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("list") || !root.get("list").isJsonArray()) {
                return json;
            }
            JsonArray srcList = root.getAsJsonArray("list");
            ArrayList<JsonObject> items = new ArrayList<>();
            for (JsonElement item : srcList) {
                if (item.isJsonObject()) {
                    items.add(item.getAsJsonObject());
                }
            }
            java.util.Collections.sort(items, new java.util.Comparator<JsonObject>() {
                @Override
                public int compare(JsonObject a, JsonObject b) {
                    long ka = getVodSortKey(a);
                    long kb = getVodSortKey(b);
                    return Long.compare(kb, ka);
                }
            });
            JsonArray dstList = new JsonArray();
            for (JsonObject item : items) {
                dstList.add(item);
            }
            root.add("list", dstList);
            return root.toString();
        } catch (Throwable th) {
            return json;
        }
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = new Gson().fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            sortFilter.add(getSortFilter(one.getAsJsonObject()));
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                sortFilter.add(getSortFilter(ele.getAsJsonObject()));
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    for (MovieSort.SortData sort : data.classes.sortList) {
                        if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                            sort.filters = sortFilters.get(sort.id);
                        }
                    }
                } else {
                    // API 不提供 filters 时，自动为每个分类注入年份过滤
                    for (MovieSort.SortData sort : data.classes.sortList) {
                        sort.filters = buildYearFilters();
                    }
                }
            } catch (Throwable th) {

            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            if (data.classes != null && data.classes.sortList != null) {
                for (MovieSort.SortData sort : data.classes.sortList) {
                    if (sort.filters == null || sort.filters.isEmpty()) {
                        sort.filters = buildYearFilters();
                    }
                    if (sort.filterSelect == null) {
                        sort.filterSelect = new java.util.HashMap<>();
                    }
                }
            }
            return data;
        } catch (Exception e) {
            LOG.e("sortXml parse error: " + e.getMessage());
            return null;
        }
    }

    private ArrayList<MovieSort.SortFilter> buildYearFilters() {
        int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        ArrayList<MovieSort.SortFilter> filters = new ArrayList<>();
        MovieSort.SortFilter yearFilter = new MovieSort.SortFilter();
        yearFilter.key = "year";
        yearFilter.name = "年份";
        yearFilter.values = new LinkedHashMap<>();
        yearFilter.values.put("全部", "");
        for (int y = currentYear; y >= 2020; y--) {
            yearFilter.values.put(String.valueOf(y), String.valueOf(y));
        }
        yearFilter.values.put("更早", "older");
        filters.add(yearFilter);
        return filters;
    }

    private void filterByYearSelection(AbsXml data, String selectedYear) {
        if (data == null || data.movie == null || data.movie.videoList == null) {
            return;
        }
        ArrayList<Movie.Video> filtered = new ArrayList<>();
        boolean isOlder = "older".equals(selectedYear);
        Integer targetYear = null;
        if (!isOlder) {
            try {
                targetYear = Integer.parseInt(selectedYear);
            } catch (Throwable th) {
                return;
            }
        }
        for (Movie.Video video : data.movie.videoList) {
            if (video == null) {
                continue;
            }
            int uiYear = video.year;
            if (uiYear <= 0) {
                continue;
            }
            if (isOlder) {
                if (uiYear < 2020) {
                    filtered.add(video);
                }
            } else if (targetYear != null && uiYear == targetYear) {
                filtered.add(video);
            }
        }
        data.movie.videoList = filtered;
    }

    private void absXml(AbsXml data, String sourceKey) {
        if (data == null) {
            return;
        }
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        if (urlInfo.urls == null) {
                            continue;
                        }
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                        for (String s : str) {
                            if (s.contains("$")) {
                                String[] ss = s.split("\\$");
                                if (ss.length >= 2) {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                }
                                //infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(s.substring(0, s.indexOf("$")), s.substring(s.indexOf("$") + 1)));
                            }
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                video.sourceKey = sourceKey;
            }
        }
    }

    private void checkThunder(AbsXml data) {
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null && video.urlBean.infoList.size() == 1) {
                Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(0);
                if (urlInfo != null && urlInfo.beanList.size() == 1 && Thunder.isSupportUrl(urlInfo.beanList.get(0).url)) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), urlInfo.beanList.get(0).url, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                LOG.i(info);
                            } else {
                                urlInfo.beanList.get(0).name = info;
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(String playList) {
                            urlInfo.urls = playList;
                            String[] str = playList.split("#");
                            List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                            for (String s : str) {
                                if (s.contains("$")) {
                                    String[] ss = s.split("\\$");
                                    if (ss.length >= 2) {
                                        infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                    }
                                }
                            }
                            urlInfo.beanList = infoBeanList;
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {

                        }
                    });
                }
            }
        }
        if (!thunderParse) {
            detailResult.postValue(data);
        }
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        try {
            if (xml == null || xml.isEmpty()) {
                throw new IllegalArgumentException("Empty XML response");
            }
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    checkThunder(data);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            LOG.e("xml parse error: " + e.getMessage() + " | cause=" + (e.getCause() != null ? e.getCause().getMessage() : "none") + " | preview=" + (xml != null ? xml.substring(0, Math.min(300, xml.length())) : "null"));
            e.printStackTrace();
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        try {
            // 测试数据
            /*json = "{\n" +
                    "\t\"list\": [{\n" +
                    "\t\t\"vod_id\": \"137133\",\n" +
                    "\t\t\"vod_name\": \"磁力测试\",\n" +
                    "\t\t\"vod_pic\": \"https:/img9.doubanio.com/view/photo/s_ratio_poster/public/p2656327176.webp\",\n" +
                    "\t\t\"type_name\": \"剧情 / 爱情 / 古装\",\n" +
                    "\t\t\"vod_year\": \"2022\",\n" +
                    "\t\t\"vod_area\": \"中国大陆\",\n" +
                    "\t\t\"vod_remarks\": \"40集全\",\n" +
                    "\t\t\"vod_actor\": \"刘亦菲\",\n" +
                    "\t\t\"vod_director\": \"杨阳\",\n" +
                    "\t\t\"vod_content\": \"　　在钱塘开茶铺的赵盼儿（刘亦菲 饰）惊闻未婚夫、新科探花欧阳旭（徐海乔 饰）要另娶当朝高官之女，不甘命运的她誓要上京讨个公道。在途中她遇到了出自权门但生性正直的皇城司指挥顾千帆（陈晓 饰），并卷入江南一场大案，两人不打不相识从而结缘。赵盼儿凭借智慧解救了被骗婚而惨遭虐待的“江南第一琵琶高手”宋引章（林允 饰）与被苛刻家人逼得离家出走的豪爽厨娘孙三娘（柳岩 饰），三位姐妹从此结伴同行，终抵汴京，见识世间繁华。为了不被另攀高枝的欧阳旭从东京赶走，赵盼儿与宋引章、孙三娘一起历经艰辛，将小小茶坊一步步发展为汴京最大的酒楼，揭露了负心人的真面目，收获了各自的真挚感情和人生感悟，也为无数平凡女子推开了一扇平等救赎之门。\",\n" +
                    "\t\t\"vod_play_from\": \"磁力测试\",\n" +
                    "\t\t\"vod_play_url\": \"0$magnet:?xt=urn:btih:9e9358b946c427962533472efdd2efd9e9e38c67&dn=%e9%98%b3%e5%85%89%e7%94%b5%e5%bd%b1www.ygdy8.com.%e7%83%ad%e8%a1%80.2022.BD.1080P.%e9%9f%a9%e8%af%ad%e4%b8%ad%e8%8b%b1%e5%8f%8c%e5%ad%97.mkv&tr=udp%3a%2f%2ftracker.opentrackr.org%3a1337%2fannounce&tr=udp%3a%2f%2fexodus.desync.com%3a6969%2fannounce\"\n" +
                    "\t}]\n" +
                    "}";*/
            AbsJson absJson = new Gson().fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml data = absJson.toAbsXml();
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    checkThunder(data);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
    }
}