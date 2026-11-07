package com.harry.faces;

import java.util.*;

/** Conservative multi-template cosine matcher. Never joins co-occurring faces. */
public final class Matcher {
    // Conservative research defaults for the InsightFace antelopev2 ResNet-100 model.
    // These must be calibrated on representative galleries before relying on the groups.
    public static final float THRESHOLD = .42f, MARGIN = .05f;
    public static float cosine(float[] a, float[] b) {
        double dot=0, aa=0, bb=0;
        if (a.length!=b.length) return -1;
        for (int i=0;i<a.length;i++) {dot+=a[i]*b[i]; aa+=a[i]*a[i]; bb+=b[i]*b[i];}
        return aa==0 || bb==0 ? -1 : (float)(dot/Math.sqrt(aa*bb));
    }
    public static long choose(float[] feature, Map<Long,List<float[]>> templates, Set<Long> excluded) {
        long bestId=-1; float best=-1, runner=-1;
        for (Map.Entry<Long,List<float[]>> e:templates.entrySet()) {
            if (excluded.contains(e.getKey())) continue;
            float score=-1;
            for (float[] t:e.getValue()) score=Math.max(score,cosine(feature,t));
            if (score>best) {runner=best; best=score; bestId=e.getKey();}
            else runner=Math.max(runner,score);
        }
        return best>=THRESHOLD && best-runner>=MARGIN ? bestId : -1;
    }

    /** Lower-cost group-to-group matching for maintenance passes. */
    public static long chooseCenter(float[] feature, Map<Long,float[]> centers, Set<Long> excluded) {
        long bestId=-1; float best=-1, runner=-1;
        for (Map.Entry<Long,float[]> e:centers.entrySet()) {
            if (excluded.contains(e.getKey())) continue;
            float score=cosine(feature,e.getValue());
            if (score>best) {runner=best; best=score; bestId=e.getKey();}
            else runner=Math.max(runner,score);
        }
        return best>=THRESHOLD && best-runner>=MARGIN ? bestId : -1;
    }
}
