package com.harry.faces;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

final class Store extends SQLiteOpenHelper {
    private static final String EMBEDDING_MODEL = "insightface-antelopev2-glintr100-v2:";
    private static final int DATABASE_VERSION = 9;
    record Person(long id,String name,byte[] thumb,int count) {String title(){return name.isEmpty()?"Person "+id:name;}}
    record Album(long id,String name,int faceCount,long coverPersonId) {}
    record SimilarPair(Person first,Person second,float similarity) {}
    record UntagResult(int removed,boolean unscanned) {}
    private record PairKey(long first,long second) {
        static PairKey of(long a,long b){return a<b?new PairKey(a,b):new PairKey(b,a);}
    }
    record Photo(String uri,long modified,long size) {String version(){return EMBEDDING_MODEL+modified+":"+size;}}
    record Found(float[] embedding,byte[] thumb) {}
    static final class ScanSession {
        final Map<Long,List<float[]>> templates;
        final Set<Long> refreshedThumbs=new HashSet<>();
        ScanSession(Map<Long,List<float[]>> templates){this.templates=templates;}
    }
    static final class ReevaluationSession {
        final Map<Long,List<float[]>> templates;
        final Map<Long,float[]> centers=new LinkedHashMap<>();
        ReevaluationSession(Map<Long,List<float[]>> templates){
            this.templates=templates;
            for(Map.Entry<Long,List<float[]>> entry:templates.entrySet()){
                float[] center=centroid(entry.getValue());
                if(center!=null)centers.put(entry.getKey(),center);
            }
        }
        int targetCount(){return centers.size();}
        void merged(long from,long to){
            List<float[]> moved=templates.remove(from);centers.remove(from);
            if(moved!=null)for(float[] sample:moved)addTemplate(templates,to,sample);
            float[] center=centroid(templates.getOrDefault(to,Collections.emptyList()));
            if(center==null)centers.remove(to);else centers.put(to,center);
        }
    }
    private static Store instance;
    private final File databaseFile;
    private List<Person> cachedPeople;
    static synchronized Store get(Context context){
        if(instance==null)instance=new Store(context.getApplicationContext());
        return instance;
    }
    private Store(Context c){
        super(c,"faces.db",null,DATABASE_VERSION);
        databaseFile=c.getDatabasePath("faces.db");
        setWriteAheadLoggingEnabled(true);
    }
    public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE person(id INTEGER PRIMARY KEY,name TEXT NOT NULL DEFAULT '',thumb BLOB)");
        d.execSQL("CREATE TABLE photo(uri TEXT PRIMARY KEY,version TEXT NOT NULL,fingerprint TEXT)");
        d.execSQL("CREATE INDEX photo_fingerprint ON photo(fingerprint)");
        d.execSQL("CREATE TABLE face(id INTEGER PRIMARY KEY,person INTEGER NOT NULL,uri TEXT NOT NULL,embedding BLOB NOT NULL,thumb BLOB)");
        d.execSQL("CREATE INDEX faces_person ON face(person)");
        d.execSQL("CREATE INDEX faces_uri ON face(uri)");
        d.execSQL("CREATE INDEX faces_person_uri ON face(person,uri)");
        d.execSQL("CREATE TABLE not_same(a INTEGER NOT NULL,b INTEGER NOT NULL,PRIMARY KEY(a,b))");
        d.execSQL("CREATE TABLE skipped_duplicate(a INTEGER NOT NULL,b INTEGER NOT NULL,PRIMARY KEY(a,b))");
        d.execSQL("CREATE TABLE face_duplicate_queue(owner INTEGER NOT NULL,candidate INTEGER NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(owner,candidate))");
        d.execSQL("CREATE TABLE photo_person_exclusion(uri TEXT NOT NULL,person INTEGER NOT NULL,PRIMARY KEY(uri,person))");
        d.execSQL("CREATE INDEX photo_person_exclusion_person ON photo_person_exclusion(person)");
        d.execSQL("CREATE TABLE album(id INTEGER PRIMARY KEY,name TEXT NOT NULL)");
        d.execSQL("CREATE TABLE album_person(album INTEGER NOT NULL,person INTEGER NOT NULL,PRIMARY KEY(album,person))");
        d.execSQL("CREATE INDEX album_people_person ON album_person(person)");
    }
    public void onUpgrade(SQLiteDatabase d,int oldVersion,int newVersion){
        if(oldVersion<2){
            // SFace and InsightFace embeddings are incompatible. A one-time clean rebuild
            // prevents old vectors from being mixed with the new 512-dimensional index.
            d.execSQL("DELETE FROM face");
            d.execSQL("DELETE FROM photo");
            d.execSQL("DELETE FROM person");
        }
        if(oldVersion<3)d.execSQL("CREATE TABLE not_same(a INTEGER NOT NULL,b INTEGER NOT NULL,PRIMARY KEY(a,b))");
        if(oldVersion<4){
            d.execSQL("ALTER TABLE photo ADD COLUMN fingerprint TEXT");
            d.execSQL("CREATE INDEX photo_fingerprint ON photo(fingerprint)");
        }
        if(oldVersion<5){
            d.execSQL("CREATE TABLE album(id INTEGER PRIMARY KEY,name TEXT NOT NULL)");
            d.execSQL("CREATE TABLE album_person(album INTEGER NOT NULL,person INTEGER NOT NULL,PRIMARY KEY(album,person))");
            d.execSQL("CREATE INDEX album_people_person ON album_person(person)");
        }
        if(oldVersion<6)d.execSQL("CREATE INDEX faces_person_uri ON face(person,uri)");
        if(oldVersion<7)d.execSQL("CREATE TABLE skipped_duplicate(a INTEGER NOT NULL,b INTEGER NOT NULL,PRIMARY KEY(a,b))");
        if(oldVersion<8)d.execSQL("CREATE TABLE face_duplicate_queue(owner INTEGER NOT NULL,candidate INTEGER NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(owner,candidate))");
        if(oldVersion<9){
            d.execSQL("CREATE TABLE photo_person_exclusion(uri TEXT NOT NULL,person INTEGER NOT NULL,PRIMARY KEY(uri,person))");
            d.execSQL("CREATE INDEX photo_person_exclusion_person ON photo_person_exclusion(person)");
        }
    }
    synchronized List<Person> people(){
        if(cachedPeople!=null)return cachedPeople;
        ArrayList<Person> list=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT p.id,p.name,COUNT(DISTINCT f.uri) FROM person p JOIN face f ON f.person=p.id GROUP BY p.id",null)){
            while(c.moveToNext())list.add(new Person(c.getLong(0),c.getString(1),null,c.getInt(2)));
        }
        // Most frequently seen people first; ties are locale-aware with named people first.
        java.text.Collator collator=java.text.Collator.getInstance();
        list.sort((a,b)->{
            int count=Integer.compare(b.count,a.count);if(count!=0)return count;
            if(a.name.isEmpty()!=b.name.isEmpty())return a.name.isEmpty()?1:-1;
            int name=collator.compare(a.name,b.name);return name==0?Long.compare(a.id,b.id):name;
        });
        cachedPeople=Collections.unmodifiableList(list);
        return cachedPeople;
    }
    private void invalidatePeople(){cachedPeople=null;}
    synchronized void invalidatePeopleCache(){invalidatePeople();}
    synchronized byte[] thumb(long id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT thumb FROM person WHERE id=?",new String[]{""+id})){
            return c.moveToFirst()?c.getBlob(0):null;
        }
    }
    synchronized List<Album> albums(){
        ArrayList<Album> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT a.id,a.name,COUNT(p.id),COALESCE(MIN(p.id),-1) FROM album a " +
                        "LEFT JOIN album_person ap ON ap.album=a.id " +
                        "LEFT JOIN person p ON p.id=ap.person GROUP BY a.id,a.name " +
                        "ORDER BY LOWER(a.name),a.id",null)){
            while(c.moveToNext())result.add(new Album(c.getLong(0),c.getString(1),
                    c.getInt(2),c.getLong(3)));
        }return result;
    }
    synchronized Album album(long id){
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT a.id,a.name,COUNT(p.id),COALESCE(MIN(p.id),-1) FROM album a " +
                        "LEFT JOIN album_person ap ON ap.album=a.id " +
                        "LEFT JOIN person p ON p.id=ap.person WHERE a.id=? GROUP BY a.id,a.name",
                new String[]{""+id})){
            return c.moveToFirst()?new Album(c.getLong(0),c.getString(1),c.getInt(2),c.getLong(3)):null;
        }
    }
    synchronized long createAlbum(String name){
        ContentValues value=new ContentValues();value.put("name",name.trim());
        return getWritableDatabase().insertOrThrow("album",null,value);
    }
    synchronized Set<Long> albumPersonIds(long album){
        LinkedHashSet<Long> result=new LinkedHashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT ap.person FROM album_person ap JOIN person p ON p.id=ap.person " +
                        "WHERE ap.album=? ORDER BY ap.rowid",new String[]{""+album})){
            while(c.moveToNext())result.add(c.getLong(0));
        }return result;
    }
    synchronized List<Person> albumPeople(long album){
        Set<Long> ids=albumPersonIds(album);ArrayList<Person> result=new ArrayList<>();
        for(Person person:people())if(ids.contains(person.id))result.add(person);
        return result;
    }
    synchronized List<String> albumPhotos(long album){
        ArrayList<String> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT f.uri FROM face f " +
                        "JOIN album_person ap ON ap.person=f.person " +
                        "WHERE ap.album=? GROUP BY f.uri ORDER BY f.uri",
                new String[]{""+album})){
            while(c.moveToNext())result.add(c.getString(0));
        }return result;
    }
    synchronized void setAlbumPeople(long album,Collection<Long> people){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            d.delete("album_person","album=?",new String[]{""+album});
            for(long person:people){
                ContentValues value=new ContentValues();value.put("album",album);value.put("person",person);
                d.insertWithOnConflict("album_person",null,value,SQLiteDatabase.CONFLICT_IGNORE);
            }
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    synchronized Person person(long id){
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT p.id,p.name,COUNT(DISTINCT f.uri) FROM person p JOIN face f ON f.person=p.id WHERE p.id=? GROUP BY p.id",
                new String[]{""+id})){
            return c.moveToFirst()?new Person(c.getLong(0),c.getString(1),null,c.getInt(2)):null;
        }
    }
    synchronized List<Person> unnamedPeople(int limit,Set<Long> excluded){
        ArrayList<Person> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT p.id,p.name,COUNT(DISTINCT f.uri) count FROM person p JOIN face f ON f.person=p.id " +
                        "WHERE p.name='' GROUP BY p.id ORDER BY count DESC,p.id",null)){
            while(c.moveToNext()&&result.size()<limit){
                long id=c.getLong(0);
                if(!excluded.contains(id))result.add(new Person(id,c.getString(1),null,c.getInt(2)));
            }
        }return result;
    }
    synchronized int scannedPhotoCount(){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM photo",null)){
            return c.moveToFirst()?c.getInt(0):0;
        }
    }
    synchronized List<String> photos(long id){
        ArrayList<String> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT uri FROM face WHERE person=? ORDER BY id DESC",new String[]{""+id})){while(c.moveToNext())result.add(c.getString(0));}
        return result;
    }
    synchronized List<String> photos(Collection<Long> people){
        LinkedHashSet<String> unique=new LinkedHashSet<>();
        SQLiteDatabase d=getReadableDatabase();
        for(long person:people)try(Cursor c=d.rawQuery(
                "SELECT DISTINCT uri FROM face WHERE person=? ORDER BY id DESC",
                new String[]{""+person})){
            while(c.moveToNext())unique.add(c.getString(0));
        }
        return new ArrayList<>(unique);
    }
    synchronized boolean processed(Photo p){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT version FROM photo WHERE uri=?",new String[]{p.uri})){return c.moveToFirst()&&c.getString(0).equals(p.version());}
    }
    synchronized boolean hasFingerprint(String uri){
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT fingerprint FROM photo WHERE uri=?",new String[]{uri})){
            return c.moveToFirst()&&!c.isNull(0)&&!c.getString(0).isEmpty();
        }
    }
    synchronized void setFingerprint(String uri,String fingerprint){
        ContentValues value=new ContentValues();value.put("fingerprint",fingerprint);
        getWritableDatabase().update("photo",value,"uri=?",new String[]{uri});
    }
    synchronized List<String> missingFingerprintUris(){
        ArrayList<String> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT uri FROM photo WHERE fingerprint IS NULL OR fingerprint='' ORDER BY uri",null)){
            while(c.moveToNext())result.add(c.getString(0));
        }return result;
    }
    synchronized boolean relinkByFingerprint(Photo photo,String fingerprint,Set<String> currentUris){
        SQLiteDatabase d=getWritableDatabase();
        try(Cursor exact=d.rawQuery("SELECT fingerprint FROM photo WHERE uri=?",new String[]{photo.uri})){
            if(exact.moveToFirst()&&!exact.isNull(0)&&fingerprint.equals(exact.getString(0))){
                ContentValues value=new ContentValues();value.put("version",photo.version());
                value.put("fingerprint",fingerprint);
                d.update("photo",value,"uri=?",new String[]{photo.uri});return true;
            }
        }
        String previous=null;
        try(Cursor matches=d.rawQuery("SELECT uri FROM photo WHERE fingerprint=? ORDER BY uri",
                new String[]{fingerprint})){
            while(matches.moveToNext()){
                String candidate=matches.getString(0);
                if(!currentUris.contains(candidate)){previous=candidate;break;}
            }
        }
        if(previous==null)return false;
        d.beginTransaction();
        try{
            d.delete("face","uri=?",new String[]{photo.uri});
            d.delete("photo","uri=?",new String[]{photo.uri});
            ContentValues stored=new ContentValues();stored.put("uri",photo.uri);
            stored.put("version",photo.version());stored.put("fingerprint",fingerprint);
            d.update("photo",stored,"uri=?",new String[]{previous});
            ContentValues faces=new ContentValues();faces.put("uri",photo.uri);
            d.update("face",faces,"uri=?",new String[]{previous});
            ContentValues exclusions=new ContentValues();exclusions.put("uri",photo.uri);
            d.update("photo_person_exclusion",exclusions,"uri=?",new String[]{previous});
            d.setTransactionSuccessful();return true;
        }finally{d.endTransaction();}
    }
    static byte[] pack(float[] f){ByteBuffer b=ByteBuffer.allocate(f.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float v:f)b.putFloat(v);return b.array();}
    static float[] unpack(byte[] bytes){FloatBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] f=new float[b.remaining()];b.get(f);return f;}
    private Map<Long,List<float[]>> templates(SQLiteDatabase d){
        Map<Long,List<float[]>> map=new LinkedHashMap<>();
        try(Cursor c=d.rawQuery("SELECT person,embedding FROM face ORDER BY id",null)){
            while(c.moveToNext()){
                List<float[]> list=map.computeIfAbsent(c.getLong(0),k->new ArrayList<>());
                // Keep up to 12 distinct templates to cover several poses.
                if(list.size()>=12)continue;
                float[] f=unpack(c.getBlob(1));
                if(list.stream().noneMatch(t->Matcher.cosine(t,f)>.90f))list.add(f);
            }
        }return map;
    }
    synchronized ScanSession beginScan(){return new ScanSession(templates(getReadableDatabase()));}
    ReevaluationSession beginUnnamedReevaluation(){
        return new ReevaluationSession(templates(getReadableDatabase()));
    }
    synchronized List<Long> unnamedPersonIds(){
        ArrayList<Long> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT p.id FROM person p JOIN face f ON f.person=p.id WHERE p.name='' " +
                        "GROUP BY p.id ORDER BY COUNT(DISTINCT f.uri) DESC,p.id",null)){
            while(c.moveToNext())result.add(c.getLong(0));
        }return result;
    }
    long reevaluateUnnamedPerson(long source,ReevaluationSession session){
        float[] center=session.centers.get(source);if(center==null)return -1;
        SQLiteDatabase d=getReadableDatabase();
        try(Cursor person=d.rawQuery("SELECT name FROM person WHERE id=?",new String[]{""+source})){
            if(!person.moveToFirst()||!person.getString(0).isEmpty())return -1;
        }
        HashSet<Long> excluded=new HashSet<>();excluded.add(source);
        for(String table:new String[]{"not_same","skipped_duplicate"})
            try(Cursor c=d.rawQuery("SELECT CASE WHEN a=? THEN b ELSE a END FROM "+table+
                            " WHERE a=? OR b=?",new String[]{""+source,""+source,""+source})){
                while(c.moveToNext())excluded.add(c.getLong(0));
            }
        try(Cursor c=d.rawQuery(
                "SELECT DISTINCT b.person FROM face a JOIN face b ON a.uri=b.uri " +
                        "WHERE a.person=? AND b.person<>?",new String[]{""+source,""+source})){
            while(c.moveToNext())excluded.add(c.getLong(0));
        }
        // The O(groups x templates) matcher made the Store monitor unavailable to the
        // UI for long stretches. Group centers keep the same threshold/margin policy,
        // need one comparison per group, and all CPU work happens outside that monitor.
        long target=Matcher.chooseCenter(center,session.centers,excluded);if(target<0)return -1;
        if(!mergeReevaluationIfValid(source,target))return -1;
        session.merged(source,target);
        return target;
    }
    private synchronized boolean mergeReevaluationIfValid(long source,long target){
        if(source==target)return false;
        SQLiteDatabase d=getReadableDatabase();
        try(Cursor person=d.rawQuery("SELECT name FROM person WHERE id=?",
                new String[]{""+source})){
            if(!person.moveToFirst()||!person.getString(0).isEmpty())return false;
        }
        try(Cursor person=d.rawQuery("SELECT 1 FROM person WHERE id=?",
                new String[]{""+target})){
            if(!person.moveToFirst())return false;
        }
        PairKey key=PairKey.of(source,target);
        for(String table:new String[]{"not_same","skipped_duplicate"})
            try(Cursor decision=d.rawQuery("SELECT 1 FROM "+table+" WHERE a=? AND b=?",
                    new String[]{""+key.first,""+key.second})){
                if(decision.moveToFirst())return false;
            }
        if(sharePhoto(source,target))return false;
        merge(source,target);return true;
    }
    private static void addTemplate(Map<Long,List<float[]>> templates,long person,float[] feature){
        List<float[]> list=templates.computeIfAbsent(person,k->new ArrayList<>());
        if(list.size()<12&&list.stream().noneMatch(t->Matcher.cosine(t,feature)>.90f))list.add(feature);
    }
    synchronized void commit(Photo p,String fingerprint,List<Found> faces,ScanSession session){
        SQLiteDatabase d=getWritableDatabase();
        boolean replacing;
        try(Cursor c=d.rawQuery("SELECT 1 FROM face WHERE uri=? LIMIT 1",new String[]{p.uri})){
            replacing=c.moveToFirst();
        }
        d.beginTransaction();
        try{
            d.delete("face","uri=?",new String[]{p.uri});
            Map<Long,List<float[]>> templates=session.templates;
            Set<Long> used=photoPersonExclusions(d,p.uri);
            for(Found f:faces){
                long person=Matcher.choose(f.embedding,templates,used);
                if(person<0){ContentValues v=new ContentValues();v.put("thumb",f.thumb);person=d.insertOrThrow("person",null,v);templates.put(person,new ArrayList<>());}
                ContentValues v=new ContentValues();v.put("person",person);v.put("uri",p.uri);v.put("embedding",pack(f.embedding));v.put("thumb",f.thumb);d.insertOrThrow("face",null,v);
                if(session.refreshedThumbs.add(person)){
                    ContentValues cover=new ContentValues();cover.put("thumb",f.thumb);
                    d.update("person",cover,"id=?",new String[]{""+person});
                }
                addTemplate(templates,person,f.embedding);
                used.add(person);
            }
            ContentValues v=new ContentValues();v.put("uri",p.uri);v.put("version",p.version());
            v.put("fingerprint",fingerprint);
            d.insertWithOnConflict("photo",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        // Modified photos are uncommon. Rebuild once for correctness so stale embeddings
        // from the old version are not used for later photos in this scan.
        if(replacing){session.templates.clear();session.templates.putAll(templates(d));}
    }
    private Set<Long> photoPersonExclusions(SQLiteDatabase d,String uri){
        HashSet<Long> result=new HashSet<>();
        try(Cursor c=d.rawQuery("SELECT person FROM photo_person_exclusion WHERE uri=?",
                new String[]{uri})){while(c.moveToNext())result.add(c.getLong(0));}
        return result;
    }
    synchronized void rename(long id,String name){ContentValues v=new ContentValues();v.put("name",name.trim());getWritableDatabase().update("person",v,"id=?",new String[]{""+id});invalidatePeople();}
    synchronized void merge(long from,long to){
        mergeAll(Collections.singletonList(from),to);
    }
    synchronized int mergeAll(Collection<Long> fromPeople,long to){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        int merged=0;
        try {
            if(personExists(d,to))for(long from:new LinkedHashSet<>(fromPeople))
                if(from!=to&&mergePerson(d,from,to))merged++;
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        if(merged>0)invalidatePeople();return merged;
    }
    private boolean personExists(SQLiteDatabase d,long person){
        try(Cursor c=d.rawQuery("SELECT 1 FROM person WHERE id=?",new String[]{""+person})){
            return c.moveToFirst();
        }
    }
    private boolean mergePerson(SQLiteDatabase d,long from,long to){
        if(!personExists(d,from))return false;
        try(Cursor memberships=d.rawQuery("SELECT album FROM album_person WHERE person=?",
                new String[]{""+from})){
            while(memberships.moveToNext()){
                ContentValues member=new ContentValues();member.put("album",memberships.getLong(0));
                member.put("person",to);
                d.insertWithOnConflict("album_person",null,member,SQLiteDatabase.CONFLICT_IGNORE);
            }
        }
        // Pair decisions belong to identities, not their temporary group IDs.
        // Carry them from the removed group to the surviving group.
        remapPairDecisions(d,"skipped_duplicate",from,to);
        remapPairDecisions(d,"not_same",from,to);
        d.execSQL("INSERT OR IGNORE INTO photo_person_exclusion(uri,person) " +
                "SELECT uri,? FROM photo_person_exclusion WHERE person=?",new Object[]{to,from});
        d.delete("photo_person_exclusion","person=?",new String[]{""+from});
        d.delete("face_duplicate_queue","owner=? OR candidate=?",
                new String[]{""+from,""+from});
        d.delete("album_person","person=?",new String[]{""+from});
        ContentValues v=new ContentValues();v.put("person",to);
        d.update("face",v,"person=?",new String[]{""+from});
        d.delete("person","id=?",new String[]{""+from});return true;
    }
    private void remapPairDecisions(SQLiteDatabase d,String table,long from,long to){
        try(Cursor decisions=d.rawQuery(
                "SELECT CASE WHEN a=? THEN b ELSE a END FROM "+table+" WHERE a=? OR b=?",
                new String[]{""+from,""+from,""+from})){
            while(decisions.moveToNext()){
                long other=decisions.getLong(0);if(other==to)continue;
                PairKey key=PairKey.of(to,other);ContentValues value=new ContentValues();
                value.put("a",key.first);value.put("b",key.second);
                d.insertWithOnConflict(table,null,value,SQLiteDatabase.CONFLICT_IGNORE);
            }
        }
        d.delete(table,"a=? OR b=?",new String[]{""+from,""+from});
    }
    synchronized void markNotSame(long first,long second){
        markAllNotSame(first,Collections.singletonList(second));
    }
    synchronized int markAllNotSame(long owner,Collection<Long> candidates){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();int changed=0;
        try{
            if(personExists(d,owner))for(long candidate:new LinkedHashSet<>(candidates)){
                if(candidate==owner||!personExists(d,candidate))continue;
                PairKey key=PairKey.of(owner,candidate);ContentValues value=new ContentValues();
                value.put("a",key.first);value.put("b",key.second);
                d.insertWithOnConflict("not_same",null,value,SQLiteDatabase.CONFLICT_IGNORE);
                d.delete("skipped_duplicate","a=? AND b=?",
                        new String[]{""+key.first,""+key.second});
                d.delete("face_duplicate_queue",
                        "(owner=? AND candidate=?) OR (owner=? AND candidate=?)",
                        new String[]{""+owner,""+candidate,""+candidate,""+owner});
                changed++;
            }
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        return changed;
    }
    synchronized void skipDuplicate(long first,long second){
        if(first==second)return;
        PairKey key=PairKey.of(first,second);ContentValues value=new ContentValues();
        value.put("a",key.first);value.put("b",key.second);
        getWritableDatabase().insertWithOnConflict(
                "skipped_duplicate",null,value,SQLiteDatabase.CONFLICT_IGNORE);
        removeQueuedDuplicate(first,second);
    }
    // Not synchronized: like reevaluateUnnamedPerson, these read-only searches load every
    // embedding and can run for seconds. Holding the Store monitor that long froze the UI,
    // whose own Store calls queued behind it. Callers tolerate groups merged meanwhile.
    List<SimilarPair> similarPairs(int limit){
        List<Person> people=people();
        if(people.size()>400)people=new ArrayList<>(people.subList(0,400));
        Map<Long,Person> byId=peopleById(people);
        Map<Long,float[]> centers=centers(people);
        Set<PairKey> rejected=pairKeys("not_same");
        Set<PairKey> skipped=pairKeys("skipped_duplicate");
        record Scored(long first,long second,float score){}
        int poolSize=Math.max(limit*4,limit);
        PriorityQueue<Scored> best=new PriorityQueue<>(Comparator.comparingDouble(Scored::score));
        List<Long> ids=new ArrayList<>(centers.keySet());
        for(int i=0;i<ids.size();i++)for(int j=i+1;j<ids.size();j++){
            long a=ids.get(i),b=ids.get(j);PairKey key=PairKey.of(a,b);
            if(rejected.contains(key)||skipped.contains(key))continue;
            float score=Matcher.cosine(centers.get(a),centers.get(b));if(score<.28f)continue;
            Scored candidate=new Scored(a,b,score);
            if(best.size()<poolSize)best.add(candidate);
            else if(score>best.peek().score()){best.poll();best.add(candidate);}
        }
        ArrayList<Scored> ranked=new ArrayList<>(best);ranked.sort((a,b)->Float.compare(b.score,a.score));
        ArrayList<SimilarPair> result=new ArrayList<>();
        for(Scored candidate:ranked){
            if(sharePhoto(candidate.first,candidate.second))continue;
            result.add(new SimilarPair(byId.get(candidate.first),byId.get(candidate.second),candidate.score));
            if(result.size()>=limit)break;
        }return result;
    }
    List<SimilarPair> similarPairsForPerson(long personId,int limit){
        Person focus=person(personId);if(focus==null||limit<=0)return Collections.emptyList();
        List<Person> people=new ArrayList<>(people());
        Map<Long,Person> byId=peopleById(people);
        Map<Long,float[]> centers=centers(people);
        float[] focusCenter=centers.get(personId);if(focusCenter==null)return Collections.emptyList();
        Set<PairKey> rejected=pairKeys("not_same");
        Set<PairKey> skipped=pairKeys("skipped_duplicate");
        // One query for every group sharing a photo, instead of a self-join per group.
        HashSet<Long> sharesPhoto=new HashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT DISTINCT b.person FROM face a JOIN face b ON a.uri=b.uri " +
                        "WHERE a.person=? AND b.person<>?",new String[]{""+personId,""+personId})){
            while(c.moveToNext())sharesPhoto.add(c.getLong(0));
        }
        ArrayList<SimilarPair> result=new ArrayList<>();
        for(Map.Entry<Long,float[]> entry:centers.entrySet()){
            long otherId=entry.getKey();if(otherId==personId)continue;
            PairKey key=PairKey.of(personId,otherId);
            if(rejected.contains(key)||skipped.contains(key)||sharesPhoto.contains(otherId))continue;
            float score=Matcher.cosine(focusCenter,entry.getValue());if(score<.28f)continue;
            Person other=byId.get(otherId);if(other!=null)result.add(new SimilarPair(focus,other,score));
        }
        result.sort((a,b)->Float.compare(b.similarity,a.similarity));
        return result.size()<=limit?result:new ArrayList<>(result.subList(0,limit));
    }
    synchronized void replaceFaceDuplicateQueue(long owner,List<SimilarPair> pairs){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            d.delete("face_duplicate_queue","owner=?",new String[]{""+owner});
            int position=0;
            for(SimilarPair pair:pairs){
                long candidate=pair.first.id==owner?pair.second.id:pair.first.id;
                if(candidate==owner)continue;
                ContentValues value=new ContentValues();value.put("owner",owner);
                value.put("candidate",candidate);value.put("position",position++);
                d.insertWithOnConflict("face_duplicate_queue",null,value,SQLiteDatabase.CONFLICT_IGNORE);
            }
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    synchronized List<Person> queuedDuplicatesForPerson(long owner){
        SQLiteDatabase d=getWritableDatabase();
        pruneFaceDuplicateQueue(d);
        ArrayList<Person> result=new ArrayList<>();
        try(Cursor c=d.rawQuery(
                "SELECT p.id,p.name,COUNT(DISTINCT f.uri),q.position FROM face_duplicate_queue q " +
                        "JOIN person p ON p.id=q.candidate JOIN face f ON f.person=p.id " +
                        "WHERE q.owner=? GROUP BY p.id,p.name,q.position ORDER BY q.position",
                new String[]{""+owner})){
            while(c.moveToNext())result.add(new Person(c.getLong(0),c.getString(1),null,c.getInt(2)));
        }
        return result;
    }
    synchronized boolean hasQueuedDuplicatesForPerson(long owner){
        SQLiteDatabase d=getWritableDatabase();pruneFaceDuplicateQueue(d);
        try(Cursor c=d.rawQuery("SELECT 1 FROM face_duplicate_queue WHERE owner=? LIMIT 1",
                new String[]{""+owner})){return c.moveToFirst();}
    }
    synchronized void removeQueuedDuplicate(long first,long second){
        getWritableDatabase().delete("face_duplicate_queue",
                "(owner=? AND candidate=?) OR (owner=? AND candidate=?)",
                new String[]{""+first,""+second,""+second,""+first});
    }
    private void pruneFaceDuplicateQueue(SQLiteDatabase d){
        d.execSQL("DELETE FROM face_duplicate_queue WHERE owner NOT IN (SELECT id FROM person) " +
                "OR candidate NOT IN (SELECT id FROM person) OR owner=candidate");
        d.execSQL("DELETE FROM face_duplicate_queue WHERE EXISTS (" +
                "SELECT 1 FROM face a JOIN face b ON a.uri=b.uri " +
                "WHERE a.person=face_duplicate_queue.owner " +
                "AND b.person=face_duplicate_queue.candidate)");
    }
    synchronized List<SimilarPair> revalidatedSkippedPairs(){
        SQLiteDatabase d=getWritableDatabase();
        d.execSQL("DELETE FROM skipped_duplicate WHERE a=b OR a NOT IN (SELECT id FROM person) OR b NOT IN (SELECT id FROM person)");
        d.execSQL("DELETE FROM skipped_duplicate WHERE EXISTS (SELECT 1 FROM not_same n WHERE n.a=skipped_duplicate.a AND n.b=skipped_duplicate.b)");
        Set<PairKey> skipped=pairKeys("skipped_duplicate");
        if(skipped.isEmpty())return Collections.emptyList();
        LinkedHashSet<Long> ids=new LinkedHashSet<>();
        for(PairKey key:skipped){ids.add(key.first);ids.add(key.second);}
        List<Person> relevant=new ArrayList<>();
        for(Person person:people())if(ids.contains(person.id))relevant.add(person);
        Map<Long,Person> byId=peopleById(relevant);
        Map<Long,float[]> centers=centers(relevant);
        ArrayList<SimilarPair> valid=new ArrayList<>();
        for(PairKey key:skipped){
            Person first=byId.get(key.first),second=byId.get(key.second);
            float[] firstCenter=centers.get(key.first),secondCenter=centers.get(key.second);
            float score=firstCenter==null||secondCenter==null?-1f:Matcher.cosine(firstCenter,secondCenter);
            if(first==null||second==null||score<.28f||sharePhoto(key.first,key.second)){
                d.delete("skipped_duplicate","a=? AND b=?",
                        new String[]{""+key.first,""+key.second});
            }else valid.add(new SimilarPair(first,second,score));
        }
        valid.sort((a,b)->Float.compare(b.similarity,a.similarity));
        return valid;
    }
    private Map<Long,Person> peopleById(Collection<Person> people){
        Map<Long,Person> result=new LinkedHashMap<>();
        for(Person person:people)result.put(person.id,person);
        return result;
    }
    private Map<Long,float[]> centers(Collection<Person> people){
        Map<Long,List<float[]>> samples=new LinkedHashMap<>();
        for(Person person:people)samples.put(person.id,new ArrayList<>());
        try(Cursor c=getReadableDatabase().rawQuery("SELECT person,embedding FROM face ORDER BY id",null)){
            while(c.moveToNext()){
                List<float[]> list=samples.get(c.getLong(0));
                if(list!=null&&list.size()<6)list.add(unpack(c.getBlob(1)));
            }
        }
        Map<Long,float[]> result=new LinkedHashMap<>();
        for(Map.Entry<Long,List<float[]>> entry:samples.entrySet()){
            float[] center=centroid(entry.getValue());if(center!=null)result.put(entry.getKey(),center);
        }
        return result;
    }
    private Set<PairKey> pairKeys(String table){
        Set<PairKey> result=new HashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT a,b FROM "+table,null)){
            while(c.moveToNext())result.add(PairKey.of(c.getLong(0),c.getLong(1)));
        }
        return result;
    }
    private boolean sharePhoto(long first,long second){
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT 1 FROM face a JOIN face b ON a.uri=b.uri WHERE a.person=? AND b.person=? LIMIT 1",
                new String[]{""+first,""+second})){return c.moveToFirst();}
    }
    private static float[] centroid(List<float[]> samples){
        if(samples.isEmpty())return null;float[] center=new float[samples.get(0).length];
        for(float[] sample:samples)for(int i=0;i<center.length&&i<sample.length;i++)center[i]+=sample[i];
        double norm=0;for(float value:center)norm+=value*value;if(norm==0)return null;norm=Math.sqrt(norm);
        for(int i=0;i<center.length;i++)center[i]/=(float)norm;return center;
    }
    synchronized boolean hasFaceInPhoto(long person,String uri){
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT 1 FROM face WHERE person=? AND uri=? LIMIT 1",
                new String[]{""+person,uri})){return c.moveToFirst();}
    }
    synchronized void separate(long person,String uri){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try(Cursor c=d.rawQuery("SELECT id,thumb FROM face WHERE person=? AND uri=?",new String[]{""+person,uri})){
            while(c.moveToNext()){
                ContentValues p=new ContentValues();p.put("thumb",c.getBlob(1));long fresh=d.insertOrThrow("person",null,p);
                ContentValues v=new ContentValues();v.put("person",fresh);d.update("face",v,"id=?",new String[]{c.getString(0)});
            } d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        invalidatePeople();
    }
    synchronized UntagResult untagFaceFromPhoto(long person,String uri){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();int removed;boolean unscanned=false;
        try{
            removed=d.delete("face","person=? AND uri=?",new String[]{""+person,uri});
            if(removed>0){
                d.delete("face_duplicate_queue","owner=? OR candidate=?",
                        new String[]{""+person,""+person});
                if(hasFaces(d,person)){
                    ContentValues exclusion=new ContentValues();exclusion.put("uri",uri);
                    exclusion.put("person",person);
                    d.insertWithOnConflict("photo_person_exclusion",null,exclusion,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }else{
                    d.delete("album_person","person=?",new String[]{""+person});
                    d.delete("person","id=?",new String[]{""+person});
                    d.delete("not_same","a=? OR b=?",new String[]{""+person,""+person});
                    d.delete("skipped_duplicate","a=? OR b=?",new String[]{""+person,""+person});
                    d.delete("photo_person_exclusion","person=?",new String[]{""+person});
                }
                try(Cursor other=d.rawQuery("SELECT 1 FROM face WHERE uri=? LIMIT 1",
                        new String[]{uri})){
                    if(!other.moveToFirst()){
                        d.delete("photo","uri=?",new String[]{uri});unscanned=true;
                    }
                }
            }
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        if(removed>0)invalidatePeople();return new UntagResult(removed,unscanned);
    }
    private boolean hasFaces(SQLiteDatabase d,long person){
        try(Cursor c=d.rawQuery("SELECT 1 FROM face WHERE person=? LIMIT 1",
                new String[]{""+person})){return c.moveToFirst();}
    }
    synchronized void removePhoto(String uri){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            String[] argument={uri};
            d.delete("face","uri=?",argument);
            d.delete("photo","uri=?",argument);
            d.delete("photo_person_exclusion","uri=?",argument);
            d.execSQL("DELETE FROM person WHERE id NOT IN (SELECT DISTINCT person FROM face)");
            d.execSQL("DELETE FROM not_same WHERE a NOT IN (SELECT id FROM person) OR b NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM skipped_duplicate WHERE a NOT IN (SELECT id FROM person) OR b NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM face_duplicate_queue WHERE owner NOT IN (SELECT id FROM person) OR candidate NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM photo_person_exclusion WHERE person NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM album_person WHERE person NOT IN (SELECT id FROM person)");
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        invalidatePeople();
    }
    synchronized void removePhotos(Collection<String> uris){
        if(uris.isEmpty())return;
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            for(String uri:uris){
                String[] argument={uri};
                d.delete("face","uri=?",argument);
                d.delete("photo","uri=?",argument);
                d.delete("photo_person_exclusion","uri=?",argument);
            }
            d.execSQL("DELETE FROM person WHERE id NOT IN (SELECT DISTINCT person FROM face)");
            d.execSQL("DELETE FROM not_same WHERE a NOT IN (SELECT id FROM person) OR b NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM skipped_duplicate WHERE a NOT IN (SELECT id FROM person) OR b NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM face_duplicate_queue WHERE owner NOT IN (SELECT id FROM person) OR candidate NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM photo_person_exclusion WHERE person NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM album_person WHERE person NOT IN (SELECT id FROM person)");
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        invalidatePeople();
    }
    // Only call after a complete query with full-library permission, never partial access.
    synchronized void reconcile(Set<String> present){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try(Cursor c=d.rawQuery("SELECT uri FROM photo",null)){
            while(c.moveToNext())if(!present.contains(c.getString(0))){String[] arg={c.getString(0)};d.delete("face","uri=?",arg);d.delete("photo","uri=?",arg);}
            try(Cursor exclusions=d.rawQuery("SELECT DISTINCT uri FROM photo_person_exclusion",null)){
                while(exclusions.moveToNext())if(!present.contains(exclusions.getString(0)))
                    d.delete("photo_person_exclusion","uri=?",new String[]{exclusions.getString(0)});
            }
            d.execSQL("DELETE FROM person WHERE name='' AND id NOT IN (SELECT person FROM face)");
            d.execSQL("DELETE FROM album_person WHERE person NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM skipped_duplicate WHERE a NOT IN (SELECT id FROM person) OR b NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM face_duplicate_queue WHERE owner NOT IN (SELECT id FROM person) OR candidate NOT IN (SELECT id FROM person)");
            d.execSQL("DELETE FROM photo_person_exclusion WHERE person NOT IN (SELECT id FROM person)");
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        invalidatePeople();
    }

    synchronized void exportBackup(OutputStream destination)throws IOException{
        SQLiteDatabase database=getWritableDatabase();
        try(Cursor checkpoint=database.rawQuery("PRAGMA wal_checkpoint(FULL)",null)){
            if(checkpoint.moveToFirst())checkpoint.getInt(0);
        }
        close();
        File source=databaseFile;
        try(InputStream input=new BufferedInputStream(new FileInputStream(source));
            GZIPOutputStream output=new GZIPOutputStream(new BufferedOutputStream(destination),64*1024)){
            copy(input,output);output.finish();
        }
    }

    synchronized int importBackup(InputStream source)throws IOException{
        File database=databaseFile;
        File directory=database.getParentFile();
        if(directory==null||(!directory.exists()&&!directory.mkdirs()))throw new IOException("Cannot open database folder");
        File incoming=new File(directory,"faces.db.import");
        File previous=new File(directory,"faces.db.before-import");
        deleteIfPresent(incoming);deleteIfPresent(previous);
        try{
            try(GZIPInputStream input=new GZIPInputStream(new BufferedInputStream(source),64*1024);
                OutputStream output=new BufferedOutputStream(new FileOutputStream(incoming))){
                copy(input,output);
            }
            validateBackup(incoming);
            close();deleteSidecars(database);
            boolean hadDatabase=database.exists();
            if(hadDatabase&&!database.renameTo(previous))throw new IOException("Cannot preserve the current database");
            if(!incoming.renameTo(database)){
                if(hadDatabase)previous.renameTo(database);
                throw new IOException("Cannot install the selected backup");
            }
            try{
                getWritableDatabase();
                int count=scannedPhotoCount();
                invalidatePeople();
                deleteIfPresent(previous);return count;
            }catch(RuntimeException error){
                close();deleteIfPresent(database);
                if(hadDatabase)previous.renameTo(database);
                throw new IOException("The backup could not be upgraded",error);
            }
        }finally{deleteIfPresent(incoming);}
    }

    private static void validateBackup(File file)throws IOException{
        SQLiteDatabase candidate=null;
        try{
            candidate=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
            int version=candidate.getVersion();
            if(version<2||version>DATABASE_VERSION)throw new IOException("Unsupported FaceGallery backup version");
            for(String query:new String[]{
                    "SELECT id,name,thumb FROM person LIMIT 1",
                    "SELECT uri,version FROM photo LIMIT 1",
                    "SELECT id,person,uri,embedding,thumb FROM face LIMIT 1"}){
                try(Cursor ignored=candidate.rawQuery(query,null)){if(ignored.moveToFirst())ignored.getColumnCount();}
            }
            // Database backups contain the complete database file. Version 5 and
            // newer therefore must also contain album names and their face links.
            if(version>=5)for(String query:new String[]{
                    "SELECT id,name FROM album LIMIT 1",
                    "SELECT album,person FROM album_person LIMIT 1"}){
                try(Cursor ignored=candidate.rawQuery(query,null)){if(ignored.moveToFirst())ignored.getColumnCount();}
            }
            if(version>=7)try(Cursor ignored=candidate.rawQuery(
                    "SELECT a,b FROM skipped_duplicate LIMIT 1",null)){
                if(ignored.moveToFirst())ignored.getColumnCount();
            }
            if(version>=8)try(Cursor ignored=candidate.rawQuery(
                    "SELECT owner,candidate,position FROM face_duplicate_queue LIMIT 1",null)){
                if(ignored.moveToFirst())ignored.getColumnCount();
            }
            if(version>=9)try(Cursor ignored=candidate.rawQuery(
                    "SELECT uri,person FROM photo_person_exclusion LIMIT 1",null)){
                if(ignored.moveToFirst())ignored.getColumnCount();
            }
            try(Cursor integrity=candidate.rawQuery("PRAGMA integrity_check",null)){
                if(!integrity.moveToFirst()||!"ok".equalsIgnoreCase(integrity.getString(0)))
                    throw new IOException("Backup database integrity check failed");
            }
        }catch(SQLiteException error){throw new IOException("This is not a valid FaceGallery backup",error);}
        finally{if(candidate!=null)candidate.close();}
    }

    private static void deleteSidecars(File database)throws IOException{
        deleteIfPresent(new File(database.getPath()+"-wal"));
        deleteIfPresent(new File(database.getPath()+"-shm"));
        deleteIfPresent(new File(database.getPath()+"-journal"));
    }
    private static void deleteIfPresent(File file)throws IOException{
        if(file.exists()&&!file.delete())throw new IOException("Cannot replace "+file.getName());
    }
    private static void copy(InputStream input,OutputStream output)throws IOException{
        byte[] buffer=new byte[64*1024];int count;
        while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
    }
}
