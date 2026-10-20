package com.getjobs.worker.boss;

import org.json.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Optional per-user API configuration, passed only to this process by the local launcher. */
final class PortableModelApi {
    static String endpoint(String base){
        URI uri=URI.create(base.trim());String host=uri.getHost();
        if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getScheme().equals("https")||uri.getScheme().equals("http")&&(host.equals("localhost")||host.equals("127.0.0.1"))))throw new IllegalArgumentException("模型地址须为HTTPS，或本机HTTP地址");
        String value=base.trim().replaceAll("/+$","");return value.endsWith("/chat/completions")||value.endsWith("/responses")?value:value+"/chat/completions";
    }
    static String send(String base,String key,String model,String prompt,int timeout){
        if(model==null||model.isBlank()||key==null||key.isBlank())throw new IllegalStateException("请先配置自己的模型名称和API密钥");
        try{
            String endpoint=endpoint(base);JSONObject body=new JSONObject().put("model",model);
            if(endpoint.endsWith("/responses"))body.put("input",prompt);else body.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",prompt)));
            HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(timeout)).header("Content-Type","application/json").header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build().send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("模型服务返回HTTP "+response.statusCode()+"，请检查地址、授权及额度");
            return parse(response.body());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("模型请求已中断",e);}catch(java.io.IOException e){throw new IllegalStateException("模型服务连接失败，请检查网络",e);}
    }
    static String parse(String text){JSONObject response=new JSONObject(text);
        if(response.has("choices"))return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        StringBuilder result=new StringBuilder();for(Object raw:response.optJSONArray("output",new JSONArray())){JSONObject item=(JSONObject)raw;for(Object part:item.optJSONArray("content",new JSONArray())){JSONObject content=(JSONObject)part;if(content.optString("type").equals("output_text"))result.append(content.getString("text"));}}
        if(result.isEmpty())throw new IllegalStateException("模型没有返回可用文本，请检查接口类型");return result.toString();
    }
}
