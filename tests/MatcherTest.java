package com.harry.faces;
import java.util.*;
public class MatcherTest {
    static void check(boolean yes){if(!yes)throw new AssertionError();}
    public static void main(String[] args){
        var templates=new LinkedHashMap<Long,List<float[]>>();
        templates.put(1L,List.of(new float[]{1,0,0},new float[]{.8f,.6f,0}));
        templates.put(2L,List.of(new float[]{0,0,1}));
        check(Matcher.choose(new float[]{1,0,0},templates,Set.of())==1);
        check(Matcher.choose(new float[]{.8f,.6f,0},templates,Set.of())==1);
        check(Matcher.choose(new float[]{1,0,0},templates,Set.of(1L))==-1);
        check(Matcher.choose(new float[]{0,-1,0},templates,Set.of())==-1);
        check(Matcher.choose(new float[]{1,0,1},templates,Set.of())==-1);
        check(Matcher.cosine(new float[]{0,0},new float[]{1,0})==-1);
        check(Matcher.cosine(new float[]{1},new float[]{1,0})==-1);
        var centers=new LinkedHashMap<Long,float[]>();
        centers.put(1L,new float[]{1,0,0});
        centers.put(2L,new float[]{0,0,1});
        check(Matcher.chooseCenter(new float[]{1,0,0},centers,Set.of())==1);
        check(Matcher.chooseCenter(new float[]{1,0,0},centers,Set.of(1L))==-1);
        check(Matcher.chooseCenter(new float[]{0,-1,0},centers,Set.of())==-1);
        check(Matcher.chooseCenter(new float[]{1,0,1},centers,Set.of())==-1);
        System.out.println("11 matcher checks passed");
    }
}
