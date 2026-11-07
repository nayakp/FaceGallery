package com.harry.faces;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.opencv.android.*;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.*;
import java.io.*;
import java.security.*;
import java.util.*;

final class Engine {
    private static final Size ARCFACE_SIZE = new Size(112,112);
    // InsightFace ArcFace canonical landmark locations for a 112 x 112 crop.
    private static final double[][] ARCFACE_LANDMARKS = {
            {38.2946,51.6963},{73.5318,51.5014},{56.0252,71.7366},
            {41.5493,92.3655},{70.7299,92.2041}
    };
    final FaceDetectorYN detector;
    final Net embedder;
    Engine(Context context)throws IOException{
        if(!OpenCVLoader.initLocal())throw new IOException("OpenCV initialization failed");
        Core.setNumThreads(2);
        detector=FaceDetectorYN.create(asset(context,"yunet.onnx"),"",new Size(320,320),.85f,.3f,5000);
        embedder=Dnn.readNetFromONNX(asset(context,"insightface_glintr100.onnx"));
        if(embedder.empty())throw new IOException("InsightFace model could not be loaded");
        // Remove the previous preview model copy after a successful migration.
        File obsolete=new File(context.getFilesDir(),"sface.onnx");
        if(obsolete.exists())obsolete.delete();
    }
    static String asset(Context c,String name)throws IOException{
        File file=new File(c.getFilesDir(),name);
        if(!file.exists()){
            File temp=new File(c.getFilesDir(),name+".tmp");
            try(InputStream in=c.getAssets().open(name);OutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
            if(!temp.renameTo(file))throw new IOException("Cannot install model");
        }return file.getAbsolutePath();
    }
    static Bitmap decode(Context c,String uri,int max)throws IOException{
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(),Uri.parse(uri)),(decoder,info,source)->{
            android.util.Size size=info.getSize();double scale=Math.min(1.,max/(double)Math.max(size.getWidth(),size.getHeight()));
            decoder.setTargetSize(Math.max(1,(int)(size.getWidth()*scale)),Math.max(1,(int)(size.getHeight()*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }
    static Bitmap thumbnail(Context context,String uri,int size)throws IOException{
        if(android.os.Build.VERSION.SDK_INT>=29){
            return context.getContentResolver().loadThumbnail(Uri.parse(uri),
                    new android.util.Size(size,size),null);
        }
        return decode(context,uri,size);
    }
    static String fingerprint(Context context,String uri)throws IOException{
        try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(uri))){
            if(input==null)throw new IOException("Photo is unavailable");
            MessageDigest digest;
            try{digest=MessageDigest.getInstance("SHA-256");}
            catch(NoSuchAlgorithmException error){throw new IOException("SHA-256 is unavailable",error);}
            byte[] buffer=new byte[128*1024];int count;
            while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);
            StringBuilder value=new StringBuilder(64);
            for(byte item:digest.digest())value.append(String.format(Locale.ROOT,"%02x",item&0xff));
            return value.toString();
        }
    }
    List<Store.Found> recognize(Context context,String uri)throws IOException{
        // 1280px keeps the bitmap plus OpenCV matrices bounded on large galleries while
        // retaining enough detail for the minimum 40px face threshold.
        Bitmap bitmap=decode(context,uri,1280);
        Mat rgba=new Mat(),bgr=new Mat(),faces=new Mat();
        List<Store.Found> result=new ArrayList<>();
        try{
            Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,bgr,Imgproc.COLOR_RGBA2BGR);
            detector.setInputSize(bgr.size());detector.detect(bgr,faces);
            for(int i=0;i<faces.rows();i++){
                float[] row=new float[15];faces.get(i,0,row);
                if(Math.min(row[2],row[3])<40)continue;
                Mat aligned=alignArcFace(bgr,row),blob=null,embedding=null;
                try{
                    if(aligned.empty())continue;
                    // Matches InsightFace ArcFaceONNX preprocessing: RGB, (pixel-127.5)/127.5.
                    blob=Dnn.blobFromImage(aligned,1.0/127.5,ARCFACE_SIZE,
                            new Scalar(127.5,127.5,127.5),true,false,CvType.CV_32F);
                    embedder.setInput(blob);embedding=embedder.forward();
                    float[] vector=new float[(int)embedding.total()];embedding.get(0,0,vector);
                    normalize(vector);
                    // Roomier portrait crop: visible space above the hair while retaining
                    // the neck/shoulders below. UI padding also improves older covers.
                    int side=Math.min(Math.min(bitmap.getWidth(),bitmap.getHeight()),
                            Math.max(1,(int)(Math.max(row[2],row[3])*1.78)));
                    int x=Math.max(0,Math.min(bitmap.getWidth()-side,
                            (int)(row[0]+row[2]/2-side/2)));
                    int y=Math.max(0,Math.min(bitmap.getHeight()-side,(int)(row[1]-.32*row[3])));
                    Bitmap crop=Bitmap.createBitmap(bitmap,x,y,side,side);
                    Bitmap small=Bitmap.createScaledBitmap(crop,192,192,true);
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,88,bytes);
                    result.add(new Store.Found(vector,bytes.toByteArray()));
                    if(small!=crop)small.recycle();if(crop!=bitmap)crop.recycle();
                }finally{
                    aligned.release();
                    if(blob!=null)blob.release();
                    if(embedding!=null)embedding.release();
                }
            }
        }finally{rgba.release();bgr.release();faces.release();bitmap.recycle();}
        return result;
    }

    /** Least-squares 2D similarity transform used by InsightFace norm_crop. */
    private static Mat alignArcFace(Mat image,float[] detection){
        double sourceX=0,sourceY=0,targetX=0,targetY=0;
        for(int i=0;i<5;i++){
            sourceX+=detection[4+i*2];sourceY+=detection[5+i*2];
            targetX+=ARCFACE_LANDMARKS[i][0];targetY+=ARCFACE_LANDMARKS[i][1];
        }
        sourceX/=5;sourceY/=5;targetX/=5;targetY/=5;
        double denominator=0,aNumerator=0,bNumerator=0;
        for(int i=0;i<5;i++){
            double x=detection[4+i*2]-sourceX,y=detection[5+i*2]-sourceY;
            double u=ARCFACE_LANDMARKS[i][0]-targetX,v=ARCFACE_LANDMARKS[i][1]-targetY;
            denominator+=x*x+y*y;aNumerator+=x*u+y*v;bNumerator+=x*v-y*u;
        }
        Mat aligned=new Mat();
        if(denominator<1e-6)return aligned;
        double a=aNumerator/denominator,b=bNumerator/denominator;
        double tx=targetX-a*sourceX+b*sourceY,ty=targetY-b*sourceX-a*sourceY;
        Mat transform=new Mat(2,3,CvType.CV_64F);
        try{
            transform.put(0,0,a,-b,tx,b,a,ty);
            Imgproc.warpAffine(image,aligned,transform,ARCFACE_SIZE,Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT,Scalar.all(0));
        }finally{transform.release();}
        return aligned;
    }

    private static void normalize(float[] vector)throws IOException{
        double length=0;for(float value:vector)length+=value*value;
        length=Math.sqrt(length);
        if(length<1e-12)throw new IOException("InsightFace returned an empty embedding");
        for(int i=0;i<vector.length;i++)vector[i]/=(float)length;
    }
}
