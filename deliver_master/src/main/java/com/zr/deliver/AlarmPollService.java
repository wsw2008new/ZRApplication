package com.zr.deliver;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Location;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.location.LocationManagerProxy;
import com.amap.api.location.LocationProviderProxy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.JsonRequest;
import com.android.volley.toolbox.Volley;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zr.deliver.model.DeliveryPolling;
import com.zr.deliver.model.OrderDetail;
import com.zr.deliver.model.OrderHistoryDelivery;
import com.zr.deliver.model.StatefulResponse;
import com.zr.deliver.util.Config;
import com.zr.deliver.util.GsonTools;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AlarmPollService extends Service implements
        AMapLocationListener {

    private RequestQueue mQueue;
    private LocationManagerProxy mAMapLocationManager = null;
    private MediaPlayer mMediaPlayer;
    private int deliverId;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e("TAG", "onStartCommand");
        activate();
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        deliverId = getSharedPreferences(Config.DELIVER_DATA, MODE_PRIVATE).getInt(Config.DELIVER_ID, -1);
        mQueue = Volley.newRequestQueue(this);
        mMediaPlayer = new MediaPlayer();
        Log.e("TAG", "onCreate");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mQueue.stop();
        deactivate();
        if (mMediaPlayer != null)
            mMediaPlayer.release();
        Log.e("TAG", "onDestroy");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void sendLocationRequest(double latitude, double longitude) {

        final DeliveryPolling polling = new DeliveryPolling();
        polling.dymanId = deliverId == -1 ? Config.DEFAULT_ID : deliverId;
        polling.latitude = latitude;
        polling.longitude = longitude;
        Gson gson = GsonTools.getGson();
        String jsonStr = gson.toJson(polling);
        JSONObject object = null;
        try {
            object = new JSONObject(jsonStr);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        Log.e("TAG", jsonStr);
        JsonRequest<JSONObject> jsonRequest = new JsonObjectRequest(Request.Method.POST,
                Config.DELIVER_ORDER_URL, object,
                new Response.Listener<JSONObject>() {
                    public void onResponse(JSONObject response) {
                        Log.d("TAG", "response -> " + response.toString());
                        String result = response.toString();
                        Gson gs = GsonTools.getGson();
                        StatefulResponse<List<OrderHistoryDelivery>> sv = gs.fromJson(result,
                                new TypeToken<StatefulResponse<List<OrderHistoryDelivery>>>() {
                                }.getType());
                        if (sv != null) {
                            ArrayList<OrderHistoryDelivery> orderList = (ArrayList<OrderHistoryDelivery>) sv.value;
                            if (orderList == null || orderList.size() == 0) return;

                            boolean isChanged = syncOrderData(orderList, polling);

                            if (isChanged) {
                                getContentResolver().notifyChange(OrderProvider.ORDER_URI, null);
                                playNoti();
                                sendOrderNotification();
                            }

                        }
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e("TAG", error.getMessage(), error);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() {
                HashMap<String, String> headers = new HashMap<>();
                headers.put("Accept", "application/json");
                headers.put("Content-Type", "application/json; charset=UTF-8");
                return headers;
            }
        };
        mQueue.add(jsonRequest);
    }

    private boolean syncOrderData(ArrayList<OrderHistoryDelivery> orderList, DeliveryPolling polling) {

        boolean isChanged = false;
        ContentResolver resolver = getContentResolver();
        Cursor c = null;
        for (OrderHistoryDelivery order : orderList) {
            c = resolver.query(OrderProvider.ORDER_URI,
                    OrderProvider.ORDER_PROJECTION, "order_id=?",
                    new String[]{order.id + ""}, null);
            if (c == null || c.getCount() == 0) {
                isChanged = true;
                ContentValues orderValues = new ContentValues();
                orderValues.put(OrderProvider.DELIVER_ID, polling.dymanId);
                orderValues.put(OrderProvider.ORDER_ID, order.id);
                orderValues.put(OrderProvider.ORDER_STATE, order.status);
                orderValues.put(OrderProvider.ADRESS, order.address);
                orderValues.put(OrderProvider.PHONE, order.telephone);
                orderValues.put(OrderProvider.TOTAL_PRICE, order.acost);
                orderValues.put(OrderProvider.DELIVER_PRICE, order.dycost);
                orderValues.put(OrderProvider.ORDER_TIME, new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").format(order.ordertime));
                orderValues.put(OrderProvider.REMARKS, order.describeContents());
                resolver.insert(OrderProvider.ORDER_URI, orderValues);

                if (order.lsc != null && order.lsc.size() > 0) {
                    for (OrderDetail orderDetail : order.lsc) {
                        ContentValues goodsValues = new ContentValues();
                        goodsValues.put(OrderProvider.ORDER_ID, order.id);
                        goodsValues.put(OrderProvider.GOOD_ID, orderDetail.goodsid);
                        goodsValues.put(OrderProvider.GOOD_NAME, orderDetail.goodsname);
                        goodsValues.put(OrderProvider.GOOD_NUM, orderDetail.buynum);
                        goodsValues.put(OrderProvider.GOOD_ICON, orderDetail.icon);
                        goodsValues.put(OrderProvider.GOOD_PRICE, orderDetail.price);
                        resolver.insert(OrderProvider.GOOD_URI, goodsValues);
                    }
                }
            }

        }

        if (c != null) {
            c.close();
        }

        return isChanged;

    }

    private void sendOrderNotification() {
        // TODO Auto-generated method stub

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
                this).setSmallIcon(R.drawable.ic_launcher)//设置图标
                .setContentTitle(getString(R.string.noti_order_hint))//设置标题
                .setContentText(getString(R.string.noti_opent_hint));//设置内容
        //设置PendingIntent，当用户点击通知跳转到另一个界面，当退出该界面，直接回到HOME*/
        Intent resultIntent = new Intent(this, OrderActivity.class);
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(OrderActivity.class);
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0,
                PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentIntent(resultPendingIntent);//设置PendingIntent

        //创建NotificationManager 对象
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = mBuilder.build();//生成Notification对象
        notification.flags = Notification.FLAG_AUTO_CANCEL;//点击后自动关闭通知
        mNotificationManager.notify(1, notification);

    }

    private void playNoti() {
        try {
            Uri alert = RingtoneManager
                    .getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setDataSource(this, alert);
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_RING);
            mMediaPlayer.prepare();
            mMediaPlayer.start();

            mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    mMediaPlayer.stop();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onLocationChanged(AMapLocation aMapLocation) {
        if (aMapLocation != null) {

            Log.e("TAG", "Latitude=" + aMapLocation.getLatitude() + "...longitude" + aMapLocation.getLongitude());
            sendLocationRequest(aMapLocation.getLatitude(), aMapLocation.getLongitude());
            //每次定位只需要获取经纬度数据，所以定位完成之后马上销毁，节省资源
            deactivate();
        }
    }

    @Override
    public void onLocationChanged(Location location) {

    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    //做一次定位
    public void activate() {

        if (mAMapLocationManager == null) {
            mAMapLocationManager = LocationManagerProxy.getInstance(this);
            Log.e("TAG", "执行定位");
        }
        mAMapLocationManager.requestLocationData(
                LocationProviderProxy.AMapNetwork, 60 * 1000, 10, this);
    }

    //销毁定位
    public void deactivate() {

        if (mAMapLocationManager != null) {
            mAMapLocationManager.removeUpdates(this);
            mAMapLocationManager.destroy();
        }
        mAMapLocationManager = null;
    }


}