package com.zeyh.casa;

import java.net.*;
import java.util.*;

public class LanWakeTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        byte[] packet=LanWake.packet("AA:BB:CC:DD:EE:02");
        check(packet.length==102,"Magic Packet must contain 102 bytes");
        for(int i=0;i<6;i++)check((packet[i]&255)==255,"Missing sync prefix");
        byte[] mac={4,(byte)217,(byte)245,(byte)128,(byte)203,(byte)170};
        for(int i=0;i<16;i++)check(Arrays.equals(Arrays.copyOfRange(packet,6+i*6,12+i*6),mac),"Incorrect MAC repetition");
        check(Arrays.equals(packet,LanWake.packet("04-d9-f5-80-cb-aa")),"MAC separators");
        for(String bad:new String[]{"", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:XX"}){
            boolean rejected=false;try{LanWake.packet(bad);}catch(java.io.IOException e){rejected=true;}check(rejected,"Invalid MAC accepted");
        }
        check(LanWake.broadcast(InetAddress.getByName("192.168.1.4"),24).getHostAddress().equals("192.168.1.255"),"/24 broadcast");
        check(LanWake.broadcast(InetAddress.getByName("192.168.2.4"),23).getHostAddress().equals("192.168.3.255"),"/23 broadcast");
        check(LanWake.broadcast(InetAddress.getByName("10.21.4.19"),16).getHostAddress().equals("10.21.255.255"),"/16 broadcast");
        for(int prefix:new int[]{0,31,32}){boolean rejected=false;try{LanWake.broadcast(InetAddress.getLoopbackAddress(),prefix);}catch(java.io.IOException e){rejected=true;}check(rejected,"Invalid subnet accepted");}
        List<String> hosts=LanWake.neighbours(InetAddress.getByName("192.168.88.250"),24);
        check(hosts.size()==253&&hosts.get(0).equals("192.168.88.1")&&hosts.get(252).equals("192.168.88.254")&&!hosts.contains("192.168.88.250"),"/24 neighbours");
        check(LanWake.neighbours(InetAddress.getByName("10.21.4.19"),16).size()==253,"Search capped to the phone's /24");
        check(LanWake.neighbours(InetAddress.getByName("192.168.1.130"),25).get(0).equals("192.168.1.129"),"/25 neighbours");
        System.out.println("PASS: Magic Packet, invalid MACs, subnet broadcasts and search ranges. No power commands sent.");
    }
}
