package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsCollector;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.video.VideoSize;

import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoViewManager;


public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    // Favor smoother VOD playback on slow networks: larger startup threshold and longer forward buffering.
    private static final int EXO_MIN_BUFFER_MS = 120000;
    private static final int EXO_MAX_BUFFER_MS = 600000;
    private static final int EXO_BUFFER_FOR_PLAYBACK_MS = 10000;
    private static final int EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 20000;
    private static final int EXO_PREBUFFER_START_PERCENT = 12;
    private static final long EXO_PREBUFFER_MAX_WAIT_MS = 20000L;

    protected Context mAppContext;
    protected SimpleExoPlayer mInternalPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;

    private PlaybackParameters mSpeedPlaybackParameters;

    private boolean mIsPreparing;
    private boolean mPreparedNotified;
    private boolean mRenderingStartedNotified;
    private long mPrepareStartElapsedMs;

    private LoadControl mLoadControl;
    private RenderersFactory mRenderersFactory;
    private TrackSelector mTrackSelector;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final Runnable mPrepareBufferRunnable = new Runnable() {
        @Override
        public void run() {
            if (mInternalPlayer == null || !mIsPreparing) {
                return;
            }
            maybeStartAfterPreBuffer();
            if (mIsPreparing) {
                mMainHandler.postDelayed(this, 500L);
            }
        }
    };

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @Override
    public void initPlayer() {
        if (mLoadControl == null) {
            mLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    EXO_MIN_BUFFER_MS,
                    EXO_MAX_BUFFER_MS,
                    EXO_BUFFER_FOR_PLAYBACK_MS,
                    EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        }

        mInternalPlayer = new SimpleExoPlayer.Builder(
                mAppContext,
                mRenderersFactory == null ? mRenderersFactory = new DefaultRenderersFactory(mAppContext) : mRenderersFactory,
                mTrackSelector == null ? mTrackSelector = new DefaultTrackSelector(mAppContext) : mTrackSelector,
                new DefaultMediaSourceFactory(mAppContext),
            mLoadControl,
                DefaultBandwidthMeter.getSingletonInstance(mAppContext),
                new AnalyticsCollector(Clock.DEFAULT))
                .build();
        setOptions();

        //播放器日志
        if (VideoViewManager.getConfig().mIsEnableLog && mTrackSelector instanceof MappingTrackSelector) {
            mInternalPlayer.addAnalyticsListener(new EventLogger((MappingTrackSelector) mTrackSelector, "ExoPlayer"));
        }

        mInternalPlayer.addListener(this);
    }

    public void setTrackSelector(TrackSelector trackSelector) {
        mTrackSelector = trackSelector;
    }

    public void setRenderersFactory(RenderersFactory renderersFactory) {
        mRenderersFactory = renderersFactory;
    }

    public void setLoadControl(LoadControl loadControl) {
        mLoadControl = loadControl;
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        // Enable disk cache for Exo sources so fetched segments can be reused and buffered more steadily.
        mMediaSource = mMediaSourceHelper.getMediaSource(path, headers, true);
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        //no support
    }

    @Override
    public void start() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.stop();
    }

    @Override
    public void prepareAsync() {
        if (mInternalPlayer == null)
            return;
        if (mMediaSource == null) return;
        if (mSpeedPlaybackParameters != null) {
            mInternalPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        mPreparedNotified = false;
        mRenderingStartedNotified = false;
        mPrepareStartElapsedMs = SystemClock.elapsedRealtime();
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource);
        mInternalPlayer.setPlayWhenReady(false);
        mInternalPlayer.prepare();
        schedulePrepareBufferCheck();
    }

    @Override
    public void reset() {
        if (mInternalPlayer != null) {
            mInternalPlayer.stop();
            mInternalPlayer.clearMediaItems();
            mInternalPlayer.setVideoSurface(null);
            mIsPreparing = false;
        }
        mPreparedNotified = false;
        mRenderingStartedNotified = false;
        stopPrepareBufferCheck();
    }

    @Override
    public boolean isPlaying() {
        if (mInternalPlayer == null)
            return false;
        int state = mInternalPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mInternalPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mInternalPlayer != null) {
            mInternalPlayer.removeListener(this);
            mInternalPlayer.release();
            mInternalPlayer = null;
        }

        mIsPreparing = false;
        mPreparedNotified = false;
        mRenderingStartedNotified = false;
        stopPrepareBufferCheck();
        mSpeedPlaybackParameters = null;
    }

    @Override
    public long getCurrentPosition() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mInternalPlayer == null)
            return 0;
        long duration = mInternalPlayer.getDuration();
        return duration == C.TIME_UNSET || duration < 0 ? 0 : duration;
    }

    @Override
    public int getBufferedPercentage() {
        return mInternalPlayer == null ? 0 : mInternalPlayer.getBufferedPercentage();
    }

    @Override
    public void setSurface(Surface surface) {
        if (mInternalPlayer != null) {
            mInternalPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null)
            setSurface(null);
        else
            setSurface(holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mInternalPlayer != null)
            mInternalPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mInternalPlayer != null)
            mInternalPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        // Delay autoplay until startup prebuffer threshold is reached.
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mInternalPlayer != null) {
            mInternalPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    @Override
    public long getTcpSpeed() {
        // no support
        return 0;
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mPlayerEventListener == null) return;
        if (mIsPreparing) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                maybeStartAfterPreBuffer();
            }
            return;
        }
        switch (playbackState) {
            case Player.STATE_BUFFERING:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case Player.STATE_READY:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case Player.STATE_ENDED:
                mPlayerEventListener.onCompletion();
                break;
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (!isPlaying || mPlayerEventListener == null) {
            return;
        }
        if (!mRenderingStartedNotified) {
            mRenderingStartedNotified = true;
            mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
        }
    }

    private void schedulePrepareBufferCheck() {
        mMainHandler.removeCallbacks(mPrepareBufferRunnable);
        mMainHandler.post(mPrepareBufferRunnable);
    }

    private void stopPrepareBufferCheck() {
        mMainHandler.removeCallbacks(mPrepareBufferRunnable);
    }

    private void notifyPreparedIfNeeded() {
        if (!mPreparedNotified && mPlayerEventListener != null) {
            mPlayerEventListener.onPrepared();
            mPreparedNotified = true;
        }
    }

    private void maybeStartAfterPreBuffer() {
        if (mInternalPlayer == null || !mIsPreparing) {
            return;
        }

        int bufferedPercent = getBufferedPercentage();
        long elapsedMs = SystemClock.elapsedRealtime() - mPrepareStartElapsedMs;
        long durationMs = mInternalPlayer.getDuration();
        boolean unknownDuration = durationMs <= 0;
        boolean reachPreBuffer = bufferedPercent >= EXO_PREBUFFER_START_PERCENT;
        boolean waitTimeout = elapsedMs >= EXO_PREBUFFER_MAX_WAIT_MS;

        if (reachPreBuffer || waitTimeout || unknownDuration) {
            notifyPreparedIfNeeded();
            mIsPreparing = false;
            stopPrepareBufferCheck();
            mInternalPlayer.setPlayWhenReady(true);
        } else if (mPlayerEventListener != null) {
            mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, bufferedPercent);
        }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }
}
