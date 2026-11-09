package com.zeyh.casa;

import java.io.IOException;
import java.net.*;
import java.util.*;

/** No Android dependencies: packet format and IPv4 subnet handling can be tested on the PC. */
final class LanWake {
    static byte[] packet(String mac) throws IOException {
        String hex=mac.replace(":","").replace("-","");
        if(!hex.matches("(?i)[0-9a-f]{12}"))throw new IOException("Endereço MAC inválido.");
        byte[] bytes=new byte[102];Arrays.fill(bytes,0,6,(byte)255);
        for(int i=0;i<96;i++)bytes[6+i]=(byte)Integer.parseInt(hex.substring((i%6)*2,(i%6)*2+2),16);
        return bytes;
    }
    static InetAddress broadcast(InetAddress address,int prefix) throws IOException {
        if(!(address instanceof Inet4Address)||prefix<1||prefix>30)throw new IOException("Rede IPv4 incompatível com Wake-on-LAN.");
        byte[] a=address.getAddress();int ip=0;for(byte b:a)ip=(ip<<8)|(b&255);
        int mask=-1<<(32-prefix),value=ip|~mask;
        return InetAddress.getByAddress(new byte[]{(byte)(value>>>24),(byte)(value>>>16),(byte)(value>>>8),(byte)value});
    }
    /** Other hosts of the phone's subnet, capped to its /24 so a search stays a few hundred probes. */
    static List<String> neighbours(InetAddress address,int prefix) throws IOException {
        if(!(address instanceof Inet4Address)||prefix<1||prefix>30)throw new IOException("Rede IPv4 incompatível com a busca.");
        byte[] a=address.getAddress();int ip=0;for(byte b:a)ip=(ip<<8)|(b&255);
        int mask=-1<<(32-Math.max(prefix,24)),net=ip&mask,last=net|~mask;List<String> hosts=new ArrayList<>();
        for(int h=net+1;h<last;h++)if(h!=ip)hosts.add((h>>>24)+"."+((h>>>16)&255)+"."+((h>>>8)&255)+"."+(h&255));
        return hosts;
    }
    static void send(DatagramSocket socket,String mac,InetAddress subnetBroadcast) throws Exception {
        byte[] bytes=packet(mac);socket.setBroadcast(true);
        Set<InetAddress> destinations=new LinkedHashSet<>();destinations.add(subnetBroadcast);destinations.add(InetAddress.getByName("255.255.255.255"));
        for(int repeat=0;repeat<3;repeat++){
            for(InetAddress dest:destinations)for(int port:new int[]{9,7})socket.send(new DatagramPacket(bytes,bytes.length,dest,port));
            Thread.sleep(100);
        }
    }
}
