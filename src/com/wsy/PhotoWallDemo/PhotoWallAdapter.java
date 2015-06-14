package com.wsy.PhotoWallDemo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import com.wsy.PhotoWallDemo.libcore.io.DiskLruCache;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

public class PhotoWallAdapter extends ArrayAdapter<String> {

    private Set<BitmapWorkerTask> taskCollection;

    private LruCache<String, Bitmap> mMemoryCache;

    private DiskLruCache mDiskLruCache;

    private GridView photoWall;

    /**
     * 每个子项目的高度
     */
    private int mItemHeight = 0;

    public PhotoWallAdapter(Context context, int textViewResourceId, String[] objects, GridView photoWall) {
        super(context, textViewResourceId, objects);

        this.photoWall = photoWall;

        taskCollection = new HashSet<BitmapWorkerTask>();

        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cache = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cache) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getAllocationByteCount();
            }
        };
        try {
            //TODO 获取图片的缓存路径
            File cacheDir = getDiskCacheDir(context, "thum");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            mDiskLruCache = DiskLruCache.open(cacheDir, getAppVersion(context), 1, 10 * 1024 * 1024);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        final String url = getItem(position);
        View ret;

        if (convertView == null) {
            ret = LayoutInflater.from(getContext()).inflate(R.layout.photo_layout, null);
        } else {
            ret = convertView;
        }

        ImageView imageView = (ImageView) ret.findViewById(R.id.photo);
        if (imageView.getLayoutParams().height != mItemHeight) {
            imageView.getLayoutParams().height = mItemHeight;
        }
        //TODO 给ImageView 设置 Tag 标记
        imageView.setTag(url);
        imageView.setImageResource(R.drawable.empty_photo);
        //加载图片
        loadBitmaps(imageView, url);

        return ret;
    }

    /**
     * 加载Bitmap对象.此方法会检查在LruCache中检查屏幕可见的ImageView的bitmap对象，
     * 如果发现任何一个ImageView的Bitmap不在缓存中，就会去下载图片
     *
     * @param imageView
     * @param url
     */
    private void loadBitmaps(ImageView imageView, String url) {
        //TODO 从LruCache缓存中获取图片
        Bitmap bitmap = getBitmapFromMemoryCache(url);
        try {
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
            } else {
                BitmapWorkerTask task = new BitmapWorkerTask();
                taskCollection.add(task);
                task.execute(url);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 从缓存中获取图片
     *
     * @param url
     * @return
     */
    private Bitmap getBitmapFromMemoryCache(String url) {
        return mMemoryCache.get(url);
    }

    /**
     * 获得当前应用程序的版本号
     *
     * @param context
     * @return
     */
    private int getAppVersion(Context context) {
        int ret;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            ret = info.versionCode;
            return ret;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return 1;
    }

    /**
     * 根据传入的uniqueName来获取硬盘缓存的路径
     *
     * @param context
     * @param uniqueName
     * @return
     */
    private File getDiskCacheDir(Context context, String uniqueName) {
        String cachePath;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !Environment.isExternalStorageRemovable()) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * 从网络下载图片
     */
    private class BitmapWorkerTask extends AsyncTask<String, Void, Bitmap> {

        //图片的Url地址
        private String imageUrl;

        @Override
        protected Bitmap doInBackground(String... params) {
            imageUrl = params[0];
            FileDescriptor fileDescriptor = null;
            FileInputStream fileInputStream = null;
            //TODO ????
            DiskLruCache.Snapshot snapshot = null;
            try {
                //生成图片URL对应的key
                final String key = hashKeyForDisk(imageUrl);
                //查找对应的缓存
                snapshot = mDiskLruCache.get(key);
                if (snapshot == null) {
                    //如果没有找到文件就从网络进行加载，并写入硬盘缓存
                    DiskLruCache.Editor editor = mDiskLruCache.edit(key);
                    if (editor != null) {
                        OutputStream outputStream = editor.newOutputStream(0);
                        if (downloadUrlToStream(imageUrl, outputStream)) {
                            editor.commit();
                        } else {
                            editor.abort();
                        }
                    }
                    //被写入硬盘缓存后 再次查找key对应的缓存
                    snapshot = mDiskLruCache.get(key);
                }
                if (snapshot != null) {
                    //TODO ?????
                    fileInputStream = (FileInputStream) snapshot.getInputStream(0);
                    //TODO getFD()什么意思？
                    fileDescriptor = fileInputStream.getFD();
                }
                //将缓存解析成Bitmap对象
                Bitmap bitmap = null;
                if (fileDescriptor != null) {
                    bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
                }
                if (bitmap != null) {
                    //将Bitmap添加到缓存中
                    addBitmapToMemoryCache(params[0], bitmap);
                }
                return bitmap;

            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (fileDescriptor == null && fileInputStream != null) {
                    try {
                        fileInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            //根据Tag找到对应的ImageView控件，将下载好的图片显示出来
            ImageView imageView = (ImageView) photoWall.findViewWithTag(imageUrl);
            if (imageView != null && bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
            taskCollection.remove(this);
        }
    }

    /**
     * 将一张图片存储到缓存当中
     *
     * @param key
     * @param bitmap
     */
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemoryCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    /**
     * 取消所有正在进行或等待任务
     */
    public void cancleAllTasks() {
        if (taskCollection != null) {
            for (BitmapWorkerTask task : taskCollection) {
                task.cancel(false);
            }
        }
    }

    /**
     * 设置子项的高度
     *
     * @param height
     */
    public void setItemHeight(int height) {
        if (height == mItemHeight) {
            return;
        }
        mItemHeight = height;
        notifyDataSetChanged();
    }

    /**
     * 将缓存记录同步到journal文件中
     */
    public void fluchCache() {
        if (mDiskLruCache != null) {
            try {
                mDiskLruCache.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 建立Http请求，并获取bitmap对象
     *
     * @param imageUrl     图片的url地址
     * @param outputStream
     * @return
     */
    private boolean downloadUrlToStream(String imageUrl, OutputStream outputStream) {
        HttpURLConnection conn = null;
        BufferedOutputStream bout = null;
        BufferedInputStream bint = null;
        try {
            URL url = new URL(imageUrl);
            conn = (HttpURLConnection) url.openConnection();
            bint = new BufferedInputStream(conn.getInputStream(), 10 * 1024);
            bout = new BufferedOutputStream(outputStream, 8 * 1024);
            int b;
            while ((b = bint.read()) != -1) {
                bout.write(b);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            try {
                if (bint != null) {
                    bint.close();
                }
                if (bout != null) {
                    bout.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * 使用MD5对传入的Key进行加密并返回
     *
     * @param imageUrl
     * @return
     */
    private String hashKeyForDisk(String imageUrl) {
        String cacheKey = null;
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(imageUrl.getBytes());
            //TODO ？？？
            cacheKey = bytesToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            //TODO ？？？
            String hex = Integer.toHexString(0xFF & digest[i]);
            if (hex.length() == 1) {
                sb.append("0");
            }
            sb.append(hex);
        }
        return sb.toString();
    }
}
