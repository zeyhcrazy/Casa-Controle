package com.zeyh.casa;

import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** LAN only. No PC bridge is needed to send the wake packets. */
final class DeviceControl {
    static final int ON=1, STANDBY=2, UNREACHABLE=3, CONNECT=4, ERROR=5;
    static final class StateInfo {
        final int kind; final String label; final boolean shutdownPending;
        StateInfo(int k,String text){this(k,text,false);}
        StateInfo(int k,String text,boolean pending){kind=k;label=text;shutdownPending=pending;}
    }
    static final class AuthorizationNeeded extends IOException {
        AuthorizationNeeded(){super("Autorize novamente o app na TV.");}
    }
    static final class PairingNeeded extends IOException {
        final String fingerprint;
        PairingNeeded(String fp) { super("Confirme o dispositivo antes de conectar."); fingerprint = fp; }
    }
    static String localHost(String host) throws IOException {
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("Informe um endereço IPv4 da sua rede de casa.");
        InetAddress ip = InetAddress.getByName(host);
        if (!ip.isSiteLocalAddress()) throw new IOException("Esta versão funciona apenas na rede local.");
        return ip.getHostAddress();
    }
    static boolean reachable(String host,int port){return reachable(host,port,1000);}
    static boolean reachable(String host,int port,int timeout){
        try(Socket s=new Socket()){s.connect(new InetSocketAddress(localHost(host),port),timeout);return true;}catch(Exception e){return false;}
    }
    /**
     * The router's DHCP can move the PC or the TV. Looks for it in the phone's subnet by the certificate it was
     * paired with; before pairing, only a single host with the port open is taken (the trust prompt still shows it).
     * Returns true when the saved address changed. Pins, token and TV key stay as they are.
     */
    static boolean locate(SharedPreferences prefs,String device,InetAddress phone,int prefix) throws Exception {
        int port=device.equals("tv")?3001:8765;String pin=prefs.getString(device+"_pin","");
        String current=prefs.getString(device+"_host",device.equals("tv")?"192.168.1.10":"192.168.1.20");
        ExecutorService pool=Executors.newFixedThreadPool(64);
        try{
            List<Future<String>> probes=new ArrayList<>();
            for(String host:LanWake.neighbours(phone,prefix))probes.add(pool.submit(()->{
                if(!reachable(host,port,600))return null;if(pin.isEmpty())return host;
                try{return MessageDigest.isEqual(discoverCertificate(host,port).getBytes(StandardCharsets.US_ASCII),pin.getBytes(StandardCharsets.US_ASCII))?host:null;}catch(Exception e){return null;}
            }));
            List<String> found=new ArrayList<>();for(Future<String> probe:probes){String host=probe.get();if(host!=null)found.add(host);}
            if(found.isEmpty()||(pin.isEmpty()&&found.size()>1)||found.contains(current))return false;
            prefs.edit().putString(device+"_host",found.get(0)).apply();return true;
        }finally{pool.shutdownNow();}
    }
    static StateInfo pcState(SharedPreferences prefs){
        if(!prefs.contains("pc_token")||!prefs.contains("pc_pin"))return reachable(prefs.getString("pc_host","192.168.1.20"),8765)?new StateInfo(CONNECT,"Na rede · conecte o app"):new StateInfo(UNREACHABLE,"Desligado ou sem conexão");
        try{JSONObject r=pc(prefs,"/api/status",null,1800);boolean pending=r.optDouble("shutdown_at",0)>0;
            return new StateInfo(ON,pending?"Ligado · desligamento agendado":r.optBoolean("dry_run")?"Ligado · modo de teste":"Ligado",pending);
        }catch(SSLException e){return new StateInfo(ERROR,"Certificado alterado · reconecte");}
        catch(Exception e){String msg=e.getMessage();if(msg!=null&&msg.contains("pareamento"))return new StateInfo(CONNECT,"Na rede · reconecte o app");return new StateInfo(UNREACHABLE,"Desligado ou sem conexão");}
    }
    static StateInfo tvState(SharedPreferences prefs){
        if(!prefs.contains("tv_key")||!prefs.contains("tv_pin")||prefs.getBoolean("tv_repair",false))return reachable(prefs.getString("tv_host","192.168.1.10"),3001)?new StateInfo(CONNECT,"Na rede · conecte o app"):new StateInfo(UNREACHABLE,"Desligada ou sem conexão");
        try(Tv tv=new Tv(prefs,false)){
            String state=tv.powerState().replace(" ","").toLowerCase(Locale.ROOT);
            if(state.equals("active"))return new StateInfo(ON,"Ligada");
            if(state.equals("screenoff"))return new StateInfo(ON,"Ligada · tela apagada");
            if(Arrays.asList("activestandby","suspend","standby","poweroff").contains(state))return new StateInfo(STANDBY,"Em espera");
            return new StateInfo(CONNECT,"Conectada · estado não informado");
        }catch(AuthorizationNeeded e){prefs.edit().putBoolean("tv_repair",true).apply();return new StateInfo(CONNECT,"Na rede · reconecte o app");}
        catch(SSLException e){return new StateInfo(ERROR,"Certificado alterado · reconecte");}
        catch(Exception e){return new StateInfo(UNREACHABLE,"Desligada ou sem conexão");}
    }
    static String fingerprint(X509Certificate cert) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder s = new StringBuilder(); for (byte b:hash) s.append(String.format(Locale.US,"%02x",b&255)); return s.toString();
    }
    static SSLSocketFactory pinnedFactory(String expected, boolean discover) throws Exception {
        SSLContext ctx=SSLContext.getInstance("TLS");
        ctx.init(null,new TrustManager[]{new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a) throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException("Client authentication not supported");}
            public void checkServerTrusted(X509Certificate[] chain,String auth) throws java.security.cert.CertificateException{
                try {
                    if(chain.length==0) throw new Exception("Certificado ausente.");
                    chain[0].checkValidity();
                    if(!discover && !MessageDigest.isEqual(fingerprint(chain[0]).getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII)))
                        throw new Exception("O certificado mudou. Confira o endereço e conecte novamente nas configurações.");
                } catch(Exception e){throw new java.security.cert.CertificateException(e);}
            }
        }},new SecureRandom()); return ctx.getSocketFactory();
    }
    static String discoverCertificate(String host,int port) throws Exception {
        localHost(host);
        Socket raw=new Socket(); raw.connect(new InetSocketAddress(host,port),4000);
        try(SSLSocket s=(SSLSocket)pinnedFactory("",true).createSocket(raw,host,port,true)){
            s.setSoTimeout(5000);s.startHandshake();return fingerprint((X509Certificate)s.getSession().getPeerCertificates()[0]);
        }
    }
    static JSONObject pc(SharedPreferences prefs,String route,JSONObject body) throws Exception {
        return pc(prefs,route,body,5000);
    }
    static JSONObject pc(SharedPreferences prefs,String route,JSONObject body,int timeout) throws Exception {
        String host=localHost(prefs.getString("pc_host","192.168.1.20"));
        String pin=prefs.getString("pc_pin",""); if(pin.isEmpty()) throw new PairingNeeded(discoverCertificate(host,8765));
        HttpsURLConnection conn=(HttpsURLConnection)new URL("https://"+host+":8765"+route).openConnection();
        conn.setSSLSocketFactory(pinnedFactory(pin,false));
        // The exact certificate hash is checked above, allowing this private IP certificate.
        conn.setHostnameVerifier((h,s)->h.equals(host));
        conn.setConnectTimeout(timeout);conn.setReadTimeout(timeout);
        conn.setRequestProperty("Authorization","Bearer "+prefs.getString("pc_token",""));
        try {
            if(body!=null){conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");try(OutputStream o=conn.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=conn.getResponseCode(); InputStream in=status<400?conn.getInputStream():conn.getErrorStream();
            if(in==null)throw new IOException("Resposta vazia do PC.");byte[] bytes=in.readNBytes(65537);if(bytes.length>65536)throw new IOException("Resposta muito grande do PC.");
            String text=new String(bytes,StandardCharsets.UTF_8);JSONObject result=new JSONObject(text);
            if(status>=400)throw new IOException(result.optString("error","Não foi possível comunicar com o PC."));return result;
        } finally {conn.disconnect();}
    }

    /** Small RFC6455 client: bounded frames, server handshake verification and ping/pong. */
    static final class Tv implements AutoCloseable {
        final SSLSocket socket; final InputStream input; final OutputStream output;
        Tv(SharedPreferences prefs) throws Exception {this(prefs,true);}
        Tv(SharedPreferences prefs,boolean interactive) throws Exception {
            String host=localHost(prefs.getString("tv_host","192.168.1.10"));
            String pin=prefs.getString("tv_pin","");if(pin.isEmpty())throw new PairingNeeded(discoverCertificate(host,3001));
            String saved=prefs.getString("tv_key","");if(!interactive&&saved.isEmpty())throw new AuthorizationNeeded();
            Socket raw=new Socket();raw.connect(new InetSocketAddress(host,3001),interactive?4000:1800);
            socket=(SSLSocket)pinnedFactory(pin,false).createSocket(raw,host,3001,true);
            try {
                socket.setSoTimeout(interactive?7000:1800);socket.startHandshake();input=socket.getInputStream();output=socket.getOutputStream();
                byte[] rnd=new byte[16];new SecureRandom().nextBytes(rnd);String key=Base64.getEncoder().encodeToString(rnd);
                output.write(("GET / HTTP/1.1\r\nHost: "+host+":3001\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();
                ByteArrayOutputStream headers=new ByteArrayOutputStream();String hs="";
                while(!hs.endsWith("\r\n\r\n")){int b=input.read();if(b<0||headers.size()>16384)throw new IOException("Resposta inválida da TV.");headers.write(b);hs=headers.toString("US-ASCII");}
                String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                String actual="";for(String line:hs.split("\r\n")){int colon=line.indexOf(':');if(colon>0&&line.substring(0,colon).equalsIgnoreCase("Sec-WebSocket-Accept"))actual=line.substring(colon+1).trim();}
                if(!hs.startsWith("HTTP/1.1 101") || !actual.equals(accept)) throw new IOException("A TV recusou a conexão.");
                JSONObject payload=new JSONObject().put("pairingType","PROMPT").put("manifest",new JSONObject().put("manifestVersion",1).put("appVersion","0.4.1").put("permissions",new JSONArray().put("CONTROL_POWER").put("CONTROL_AUDIO").put("CONTROL_MOUSE_AND_KEYBOARD").put("READ_NETWORK_STATE")));
                if(!saved.isEmpty())payload.put("client-key",saved);payload.put("forcePairing",false);
                send(new JSONObject().put("type","register").put("id","pair").put("payload",payload));
                socket.setSoTimeout(interactive?45000:1800);long until=System.currentTimeMillis()+(interactive?45000:2500);boolean registered=false;
                while(System.currentTimeMillis()<until){JSONObject msg=receive();if(msg.optString("type").equals("registered")){
                    String k=msg.getJSONObject("payload").getString("client-key");if(!k.equals(saved)||prefs.getBoolean("tv_repair",false))prefs.edit().putString("tv_key",k).remove("tv_repair").apply();registered=true;break;
                }if(!interactive){JSONObject p=msg.optJSONObject("payload");if(msg.optString("type").equals("error")||(p!=null&&p.has("pairingType")))throw new AuthorizationNeeded();}
                if(msg.optString("type").equals("error"))throw new IOException(msg.optString("error","A TV recusou a autorização."));}
                if(!registered)throw new IOException("Confirme a autorização na tela da TV e tente novamente.");
                socket.setSoTimeout(interactive?5000:1800);
            }catch(Exception e){socket.close();throw e;}
        }
        void send(JSONObject obj) throws Exception {frame(1,obj.toString().getBytes(StandardCharsets.UTF_8));}
        void frame(int opcode,byte[] bytes)throws IOException{
            byte[] mask=new byte[4];new SecureRandom().nextBytes(mask);output.write(0x80|opcode);
            if(bytes.length<126)output.write(0x80|bytes.length);else{output.write(0x80|126);output.write(bytes.length>>8);output.write(bytes.length&255);}
            output.write(mask);for(int i=0;i<bytes.length;i++)output.write(bytes[i]^mask[i%4]);output.flush();
        }
        int octet()throws IOException{int b=input.read();if(b<0)throw new EOFException("A TV encerrou a conexão.");return b;}
        JSONObject receive() throws Exception {
            ByteArrayOutputStream message=new ByteArrayOutputStream();
            while(true){int first=octet(),second=octet(),op=first&15;long len=second&127;
                if(len==126)len=(octet()<<8)|octet();else if(len==127){len=0;for(int i=0;i<8;i++)len=(len<<8)|octet();}
                if(len<0||len>1048576||message.size()+len>1048576)throw new IOException("Mensagem muito grande da TV.");
                byte[] mask=(second&128)!=0?input.readNBytes(4):null;byte[] bytes=input.readNBytes((int)len);
                if(bytes.length!=len)throw new EOFException();if(mask!=null){if(mask.length!=4)throw new EOFException();for(int i=0;i<bytes.length;i++)bytes[i]^=mask[i%4];}
                if(op==8)throw new EOFException("A TV encerrou a conexão.");if(op==9){frame(10,bytes);continue;}if(op==10)continue;
                if(op!=0&&op!=1)throw new IOException("Formato de mensagem não suportado.");message.write(bytes);
                if((first&128)!=0)return new JSONObject(message.toString("UTF-8"));
            }
        }
        String powerState()throws Exception{
            send(new JSONObject().put("type","request").put("id","status").put("uri","ssap://com.webos.service.tvpower/power/getPowerState"));
            for(int i=0;i<5;i++){JSONObject r=receive();if(r.optString("type").equals("error"))return "unknown";if(r.optString("id").equals("status")){JSONObject p=r.optJSONObject("payload");return p==null?"unknown":p.optString("state","unknown");}}
            return "unknown";
        }
        JSONObject request(String uri,JSONObject payload)throws Exception{
            String id=UUID.randomUUID().toString();
            send(new JSONObject().put("type","request").put("id",id).put("uri",uri).put("payload",payload));
            for(int i=0;i<10;i++){JSONObject r=receive();if(!r.optString("id").equals(id))continue;
                if(r.optString("type").equals("error"))throw new IOException(r.optString("error","A TV recusou o comando."));
                JSONObject value=r.optJSONObject("payload");if(value==null)throw new IOException("Resposta vazia da TV.");
                if(value.has("returnValue")&&!value.optBoolean("returnValue"))throw new IOException(value.optString("errorText","A TV recusou o comando."));return value;
            }throw new IOException("A TV não respondeu ao comando.");
        }
        void off()throws Exception {
            send(new JSONObject().put("type","request").put("id","off").put("uri","ssap://system/turnOff").put("payload",new JSONObject()));
            try{while(true){JSONObject r=receive();if(r.optString("type").equals("error"))throw new IOException(r.optString("error"));if(r.optString("id").equals("off")){
                JSONObject p=r.optJSONObject("payload");if(p!=null&&p.has("returnValue")&&!p.optBoolean("returnValue"))throw new IOException("A TV recusou o desligamento.");return;
            }}}catch(EOFException ignored){/* TV can close the connection when shutting down. */}
        }
        public void close()throws IOException{socket.close();}
    }

    static final class Pointer implements AutoCloseable{
        final SSLSocket socket;final OutputStream out;
        Pointer(SharedPreferences prefs,String socketPath)throws Exception{
            URI uri=new URI(socketPath);String host=localHost(prefs.getString("tv_host","192.168.1.10"));
            if(!uri.getScheme().equals("wss")||!host.equals(uri.getHost())||uri.getPort()!=3001||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IOException("A TV retornou um endereço de controle inesperado.");
            String path=uri.getRawPath();if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
            Socket raw=new Socket();raw.connect(new InetSocketAddress(host,3001),4000);
            socket=(SSLSocket)pinnedFactory(prefs.getString("tv_pin",""),false).createSocket(raw,host,3001,true);
            try{
                socket.setSoTimeout(5000);socket.startHandshake();InputStream in=socket.getInputStream();out=socket.getOutputStream();
                byte[] random=new byte[16];new SecureRandom().nextBytes(random);String key=Base64.getEncoder().encodeToString(random);
                out.write(("GET "+path+" HTTP/1.1\r\nHost: "+host+":3001\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
                ByteArrayOutputStream headers=new ByteArrayOutputStream();String hs="";
                while(!hs.endsWith("\r\n\r\n")){int b=in.read();if(b<0||headers.size()>16384)throw new IOException("Falha ao conectar o controle.");headers.write(b);hs=headers.toString("US-ASCII");}
                String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                boolean matched=false;for(String line:hs.split("\r\n")){int colon=line.indexOf(':');if(colon>0&&line.substring(0,colon).equalsIgnoreCase("Sec-WebSocket-Accept")&&line.substring(colon+1).trim().equals(accept))matched=true;}
                if(!hs.startsWith("HTTP/1.1 101")||!matched)throw new IOException("A TV recusou a conexão do controle.");
            }catch(Exception e){socket.close();throw e;}
        }
        synchronized void button(String name)throws Exception{
            if(!Arrays.asList("UP","DOWN","LEFT","RIGHT","ENTER","BACK","HOME","MENU","PLAY","PAUSE","REWIND","FASTFORWARD","VOLUMEUP","VOLUMEDOWN","MUTE").contains(name))throw new IOException("Tecla de TV não permitida.");
            byte[] bytes=("type:button\nname:"+name+"\n\n").getBytes(StandardCharsets.UTF_8);byte[] mask=new byte[4];new SecureRandom().nextBytes(mask);
            out.write(0x81);out.write(0x80|bytes.length);out.write(mask);for(int i=0;i<bytes.length;i++)out.write(bytes[i]^mask[i%4]);out.flush();
        }
        public void close()throws IOException{socket.close();}
    }
}
