package org.alt17.catimage;


import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.provider.MediaStore;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class CatLoader {

    /**
     * Choose a type to load the image from
     */
    public static final int FROM_URL = 1;
    public static final int FROM_ASSETS = 2;
    public static final int FROM_RESOURCES = 3;
    public static final int FROM_ANDROID_ICON = 4;
    public static final int FROM_VIDEO_THUMBNAIL = 5;
    private static final int TIMEOUT_LOADER = 1000 * 60; // 60 seconds

    MemoryCache memoryCache = new MemoryCache();
    FileCache fileCache;
    ExecutorService executorService;

    /**
     * handler to display images in UI thread
     */
    Handler handler = new Handler();
    private Map<ImageView, String> imageViews = Collections.synchronizedMap(new WeakHashMap<ImageView, String>());
    private Context mContext;
    private int loadingImg;

    public CatLoader(Context context) {
        this.mContext = context;
        fileCache = new FileCache(context);
        executorService = Executors.newFixedThreadPool(5);
    }

    public static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null)
            return null;
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    /**
     * Display the desired image async into the imageview
     *
     * @param url,        if it's http image, full assets path (e.x icons/android.png) or package name if its from android
     * @param id,         not used if type != FROM_RESOURCES, if type is FROM_RESOURCES then set the id from resources (e.x R.id.android)
     * @param type,       type of image to download
     * @param imageView,  the image view container to set the image
     * @param loadingImg, the loading image desired
     */
    public void displayImage(String url, int id, int type, ImageView imageView, int loadingImg) {
        this.loadingImg = loadingImg;
        imageViews.put(imageView, url);
        Bitmap bitmap = memoryCache.get(url);
        if (bitmap != null)
            imageView.setImageBitmap(bitmap);
        else {
            queueCat(url, id, type, imageView);
            imageView.setImageResource(loadingImg);
        }
    }

    private void queueCat(String url, int id, int type, ImageView imageView) {
        CatToLoad p = new CatToLoad(url, id, type, imageView);
        executorService.submit(new Loader(p));
    }

    private Bitmap getBitmapFromUrl(String url) {
        File f = fileCache.getFile(url);

        //from SD cache
        Bitmap b = decodeFile(f);
        if (b != null)
            return b;

        //from web
        try {
            Bitmap bitmap = null;
            URL imageUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) imageUrl.openConnection();
            conn.setConnectTimeout(TIMEOUT_LOADER);
            conn.setReadTimeout(TIMEOUT_LOADER);
            conn.setInstanceFollowRedirects(true);
            InputStream is = conn.getInputStream();
            OutputStream os = new FileOutputStream(f);
            Utils.CopyStream(is, os);
            os.close();
            bitmap = decodeFile(f);
            return bitmap;
        } catch (Throwable ex) {
            if (ex instanceof OutOfMemoryError)
                memoryCache.clear();
            return null;
        }
    }

    /**
     * @param name
     * @return
     */
    private Bitmap getBitmapFromAssets(String name) {
        File f = fileCache.getFile(name);

        //from SD cache
        Bitmap b = decodeFile(f);
        if (b != null)
            return b;

        //from web
        try {
            InputStream ins = mContext.getAssets().open(name);
            return BitmapFactory.decodeStream(ins);
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (ex instanceof OutOfMemoryError)
                memoryCache.clear();
            return null;
        }
    }

    /**
     * decodes image and scales it to reduce memory consumption
     *
     * @param f file to save and cache the image
     * @return a resized bitmap
     */
    private Bitmap decodeFile(File f) {
        try {
            //decode image size
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            FileInputStream stream1 = new FileInputStream(f);
            BitmapFactory.decodeStream(stream1, null, o);
            stream1.close();

            //Find the correct scale value. It should be the power of 2.
            final int REQUIRED_SIZE = 70;
            int width_tmp = o.outWidth, height_tmp = o.outHeight;
            int scale = 1;
            while (true) {
                if (width_tmp / 2 < REQUIRED_SIZE || height_tmp / 2 < REQUIRED_SIZE)
                    break;
                width_tmp /= 2;
                height_tmp /= 2;
                scale *= 2;
            }

            if (scale >= 2) {
                scale /= 2;
            }

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            FileInputStream stream2 = new FileInputStream(f);
            Bitmap bitmap = BitmapFactory.decodeStream(stream2, null, o2);
            stream2.close();
            return bitmap;
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    boolean imageViewReused(CatToLoad catToLoad) {
        String tag = imageViews.get(catToLoad.imageView);
        if (tag == null || !tag.equals(catToLoad.url))
            return true;
        return false;
    }

    public Bitmap getBitmapFromAndroid(String pkg) throws NameNotFoundException {

        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
        int iconId = ai.icon;
        return drawableToBitmap(AndroidHelper.getFullResIcon(mContext, pkg, iconId));
    }

    /**
     * Clear cache, file and memory
     */
    public void clearCache() {
        memoryCache.clear();
        fileCache.clear();
    }

    /**
     * CatLoader task class
     * Creates a new task to be load async into a ImageView
     */
    private class CatToLoad {
        public String url;
        public ImageView imageView;
        public int id;
        public int type;

        public CatToLoad(String u, int id, int type, ImageView i) {
            this.url = u;
            this.imageView = i;
            this.type = type;
            this.id = id;
        }
    }

    class Loader implements Runnable {
        CatToLoad catToLoad;

        Loader(CatToLoad catToLoad) {
            this.catToLoad = catToLoad;
        }

        @Override
        public void run() {
            try {
                if (imageViewReused(catToLoad))
                    return;
                Bitmap bmp = null;
                switch (catToLoad.type) {
                    case FROM_URL:
                        bmp = getBitmapFromUrl(catToLoad.url);
                        break;
                    case FROM_ASSETS:
                        bmp = getBitmapFromAssets(catToLoad.url);
                        break;
                    case FROM_RESOURCES:
                        bmp = BitmapFactory.decodeResource(mContext.getResources(), catToLoad.id);
                        break;
                    case FROM_ANDROID_ICON:
                        bmp = getBitmapFromAndroid(catToLoad.url);
                        break;
                    case FROM_VIDEO_THUMBNAIL:
                        bmp = ThumbnailUtils.createVideoThumbnail(catToLoad.url, MediaStore.Images.Thumbnails.MINI_KIND);
                        break;
                }

                memoryCache.put(catToLoad.url, bmp);
                if (imageViewReused(catToLoad))
                    return;

                BitmapDisplayer bd = new BitmapDisplayer(bmp, catToLoad);
                handler.post(bd);

            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    /**
     * Used to display bitmap in the UI thread
     */
    class BitmapDisplayer implements Runnable {
        Bitmap bitmap;
        CatToLoad catToLoad;

        public BitmapDisplayer(Bitmap b, CatToLoad p) {
            bitmap = b;
            catToLoad = p;
        }

        public void run() {
            if (imageViewReused(catToLoad))
                return;
            if (bitmap != null)
                catToLoad.imageView.setImageBitmap(bitmap);
            else
                catToLoad.imageView.setImageResource(loadingImg);
        }
    }

}
