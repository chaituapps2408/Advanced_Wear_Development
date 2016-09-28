/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String TAG = SunshineWatchFace.class.getSimpleName();

    private static final String HIGHEST_TEMPERATURE_KEY = "highestTemperature";
    private static final String LOWEST_TEMPERATURE_KEY = "lowestTemperature";
    private static final String WEATHER_ICON_KEY = "weatherIcon";
    public static final String SUNSHINE_WEATHER_PATH = "/sunshine_weather";


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine  implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        //Paint mTextPaint;
        Paint mTextPaintHrMn;
        Paint mTextPaintSecond;
        Paint mTextPaintDate;
        Paint mTextPaintTempHigh;
        Paint mTextPaintTempLow;
        boolean mAmbient;
        Calendar mCalendar;
        private Bitmap weatherBitmap;
        Date date;
        SimpleDateFormat dayOfWeekFormat;
        java.text.DateFormat dateFormat;

        private String highestTemperature = String.format(getResources().getString(R.string.format_temperature), "25");

        private String lowestTemperature = String.format(getResources().getString(R.string.format_temperature), "16");


        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background_interactive));

            mTextPaintHrMn = new Paint();
            mTextPaintHrMn = createTextPaint(resources.getColor(R.color.text_time), BOLD_TYPEFACE);

            mTextPaintSecond = new Paint();
            mTextPaintSecond = createTextPaint(resources.getColor(R.color.text_time), NORMAL_TYPEFACE);

            mTextPaintDate = new Paint();
            mTextPaintDate = createTextPaint(resources.getColor(R.color.text_date), NORMAL_TYPEFACE);


            mTextPaintTempHigh = new Paint();
            mTextPaintTempHigh = createTextPaint(resources.getColor(R.color.text_time), NORMAL_TYPEFACE);

            mTextPaintTempLow = new Paint();
            mTextPaintTempLow = createTextPaint(resources.getColor(R.color.text_date), NORMAL_TYPEFACE);


            mCalendar = Calendar.getInstance();
            date = new Date();
            initFormats();

          /*  int weatherIconSize = Float.valueOf(getResources().getDimension(R.dimen.weather_icon_size)).intValue();
            weatherBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.art_clear),
                    weatherIconSize, weatherIconSize, false);
*/


        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float textSizeDate = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_date_round : R.dimen.digital_text_size_date);

            float textSizeTemp = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_temp_round : R.dimen.digital_text_size_temp);

            mTextPaintHrMn.setTextSize(textSize);
            mTextPaintSecond.setTextSize(textSize);

            mTextPaintDate.setTextSize(textSizeDate);

            mTextPaintTempHigh.setTextSize(textSizeTemp);
            mTextPaintTempLow.setTextSize(textSizeTemp);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaintHrMn.setAntiAlias(!inAmbientMode);
                    mTextPaintSecond.setAntiAlias(!inAmbientMode);
                    mTextPaintDate.setAntiAlias(!inAmbientMode);
                    mTextPaintTempHigh.setAntiAlias(!inAmbientMode);
                    mTextPaintTempLow.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            date.setTime(now);

            /*String hrText = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));*/

            /*String text = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
*/

            canvas.drawLine(bounds.width() / 2 - 40, bounds.height() / 2, bounds.width() / 2 + 40, bounds.height() / 2, mTextPaintDate);

            if (mAmbient) {
                String fullTime = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                        mCalendar.get(Calendar.MINUTE));
                String hrTime = String.format("%d:", mCalendar.get(Calendar.HOUR));
                String minTime = String.format("%02d", mCalendar.get(Calendar.MINUTE));
                float startX = bounds.width() / 2 - mTextPaintHrMn.measureText(fullTime) / 2;
                float minStartX = startX + mTextPaintHrMn.measureText(hrTime);
                float startY = (bounds.height() / 2) - mYOffset;
                canvas.drawText(hrTime, startX, startY, mTextPaintHrMn);
                canvas.drawText(minTime, minStartX, startY, mTextPaintSecond);

                float startYWeather = (bounds.height() / 2) + mYOffset;


                float highTempStartX = bounds.width() / 2 - mTextPaintTempHigh.measureText(highestTemperature + lowestTemperature) / 2;

                canvas.drawText(highestTemperature, highTempStartX, startYWeather, mTextPaintTempHigh);

                float lowTempStartX = highTempStartX + mTextPaintTempHigh.measureText(highestTemperature) + 10;

                canvas.drawText(lowestTemperature, lowTempStartX, startYWeather, mTextPaintTempLow);


            } else {
                String fullTime = String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                        mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
                String hrMinTime = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                        mCalendar.get(Calendar.MINUTE));
                String secTime = String.format(":%02d", mCalendar.get(Calendar.SECOND));
                float startX = bounds.width() / 2 - mTextPaintHrMn.measureText(fullTime) / 2;
                float secStartX = startX + mTextPaintHrMn.measureText(hrMinTime);
                float startY = (bounds.height() / 2) - mYOffset - mYOffset / 2;

                canvas.drawText(hrMinTime, startX, startY, mTextPaintHrMn);
                canvas.drawText(secTime, secStartX, startY, mTextPaintSecond);


                String dateText = dayOfWeekFormat.format(date);
                float dateTextWidth = mTextPaintDate.measureText(dateText);
                float dateXPosition = (bounds.width() - dateTextWidth) / 2;
                float startYDate = (bounds.height() / 2) - mYOffset / 2;

                canvas.drawText(dateText, dateXPosition, startYDate, mTextPaintDate);

                float startYWeather = (bounds.height() / 2) + mYOffset;


                float highTempStartX = bounds.width() / 2 - mTextPaintTempHigh.measureText(highestTemperature) / 2;

                canvas.drawText(highestTemperature, highTempStartX, startYWeather, mTextPaintTempHigh);

                float lowTempStartX = highTempStartX + mTextPaintTempHigh.measureText(highestTemperature) + 10;

                canvas.drawText(lowestTemperature, lowTempStartX, startYWeather, mTextPaintTempLow);

                if (weatherBitmap != null) {
                    float startXImage = highTempStartX - weatherBitmap.getScaledWidth(canvas) - 10;
                    canvas.drawBitmap(weatherBitmap, startXImage, (bounds.height() / 2) +5 , mTextPaintTempLow);
                }

            }

        }


        private void initFormats() {
            dayOfWeekFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            dayOfWeekFormat.setCalendar(mCalendar);
            dateFormat = DateFormat.getDateFormat(SunshineWatchFace.this);
            dateFormat.setCalendar(mCalendar);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "Connected to Synchronized API");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Connection to Synchronized API has been suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "Connection to Synchronized API has failed :"+connectionResult.getErrorMessage());
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {

            Log.d(TAG, "New Data received");
            for (DataEvent event : dataEvents) {
                if (event.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem item = event.getDataItem();

                if (!item.getUri().getPath().equals(
                        SUNSHINE_WEATHER_PATH)) {
                    continue;
                }

                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                final String latestHighestTemperature = dataMap.getString(HIGHEST_TEMPERATURE_KEY);
                final String latestLowestTemperature = dataMap.getString(LOWEST_TEMPERATURE_KEY);

                if (TextUtils.isEmpty(latestHighestTemperature) || TextUtils.isEmpty(latestLowestTemperature))
                    return;

                if (!latestHighestTemperature.equalsIgnoreCase(highestTemperature)
                        || !latestLowestTemperature.equalsIgnoreCase(lowestTemperature)) {

                    final Asset iconAsset = dataMap.getAsset(WEATHER_ICON_KEY);

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            updateData(latestHighestTemperature, latestLowestTemperature, iconAsset);
                        }
                    }).start();
                }

            }
        }

        private void updateData(String latestHighestTemperature, String latestLowestTemperature, Asset iconAsset) {
            Bitmap weatherIcon = assetToBitmap(iconAsset);
            int weatherIconSize = Float.valueOf(getResources().getDimension(R.dimen.weather_icon_size)).intValue();
            weatherBitmap = Bitmap.createScaledBitmap(weatherIcon, weatherIconSize, weatherIconSize, false);
            highestTemperature = latestHighestTemperature;
            lowestTemperature = latestLowestTemperature;
            invalidate();
            updateTimer();
        }
        public Bitmap assetToBitmap(Asset asset) {
            if (asset == null)
                return null;

            ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
            if (!result.isSuccess())
                return null;

            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null)
                return null;

            return BitmapFactory.decodeStream(assetInputStream);
        }
    }
}
