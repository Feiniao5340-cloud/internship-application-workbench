package com.getjobs.worker.parallel;

import java.util.*;

public final class Platforms {
    public static final List<String> IDS=List.of("boss","shixiseng","zhilian","job51","lagou");
    private Platforms() {}
    public static String require(String id) {if(!IDS.contains(id))throw new IllegalArgumentException("未知平台");return id;}
    public static boolean available(String id){return !require(id).equals("lagou");}
    public static String label(String id) {return switch(require(id)){case "boss"->"BOSS直聘";case "shixiseng"->"实习僧";case "zhilian"->"智联招聘";case "job51"->"应届生实习";default->"拉勾";};}
    public static String home(String id) {return switch(require(id)){case "boss"->"https://www.zhipin.com/web/geek/jobs";case "shixiseng"->"https://www.shixiseng.com/";case "zhilian"->"https://www.zhaopin.com/";case "job51"->"https://q.yingjiesheng.com/pc/searchintention";default->"https://www.lagou.com/";};}
    public static boolean protectedAccount(String id) {return id.equals("boss")||id.equals("shixiseng");}
}
