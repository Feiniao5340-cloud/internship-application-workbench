package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import org.json.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** A text-only classifier. It never receives browser control or permission to send messages. */
@Service
public class InternshipModel {
    private final AiService api;
    @Value("${internship.model-provider:codex}") private String provider = "codex";
    @Value("${internship.codex-model:gpt-6-luna}") private String codexModel = "gpt-6-luna";
    @Value("${internship.codex-fast-mode:true}") private boolean fastMode=true;
    @Value("${internship.codex-executable:}") private String executable = "";
    @Value("${internship.model-timeout-seconds:180}") private int timeout = 180;
    @Value("${internship.model-proxy:}") private String proxy = "";
    private volatile String state = "尚未测试模型连接";
    private volatile boolean ready;
    public InternshipModel(AiService api) { this.api = api; }
    public Map<String,Object> status() {
        return Map.of("provider",provider,"model",provider.equals("codex")?codexModel:"API配置","ready",ready,"message",state,"speed",fastMode?"Fast（请求加速）":"Standard");
    }
    public String classify(String prompt, BooleanSupplier stopped) {
        try {
            String answer = provider.equals("codex") ? codex(prompt, stopped) : apiRequest(prompt);
            new JSONObject(answer);
            ready=true; state="模型连接正常";
            return answer;
        } catch(Exception e) {
            ready=false;
            state = stopped.getAsBoolean() ? "模型任务已暂停"
                    : e instanceof ModelFailure ? e.getMessage()
                    : e instanceof java.util.concurrent.TimeoutException ? "模型响应超时，请稍后重试或检查网络"
                    : e instanceof org.json.JSONException ? "模型返回数据不符合JSON约定，未执行投递"
                    : "模型调用失败，请检查本机程序和网络";
            throw new IllegalStateException(state,e);
        }
    }
    private String apiRequest(String prompt) {
        if(!provider.equals("api")) throw new IllegalArgumentException("模型来源无效");
        String key=System.getenv("WORKBENCH_API_KEY"),base=System.getenv("WORKBENCH_API_BASE"),modelName=System.getenv("WORKBENCH_API_MODEL");
        if(base!=null&&!base.isBlank())return PortableModelApi.send(base,key,modelName,prompt,timeout);
        return api.sendRequest(prompt);
    }
    public Map<String,Object> check(BooleanSupplier stopped) {
        // Entirely fictional capability probe, independent of any saved user's profile.
        JSONObject c=InternshipPolicy.defaults().put("confirmed",true).put("resumeText","虚构测试人物：完成过示例库存数据清洗与图表练习。")
                .put("targetDirections","库存数据分析实习").put("excludedDirections","装卸搬运执行");
        JSONObject j=new JSONObject().put("title","库存数据分析实习生").put("salary","240元/天")
                .put("jd","负责库存数据清洗与图表制作，招募数据分析实习生，不承担装卸搬运。");
        JSONObject result=InternshipPolicy.evaluate(c,j,classify(InternshipPolicy.prompt(c,j),stopped));
        JSONObject legal=new JSONObject(j.toString()).put("title","仓库作业实习生")
                .put("jd","负责装卸搬运执行，不涉及数据分析。招募仓库作业实习生。");
        JSONObject negative=InternshipPolicy.evaluate(c,legal,classify(InternshipPolicy.prompt(c,legal),stopped));
        if(!result.optString("decision").equals("apply") || !negative.optString("decision").equals("skip")) {
            ready=false; state="模型连接已返回，但岗位判断校验未通过";
        }
        return Map.of("connection",status(),"sample",result.toMap(),"negativeSample",negative.toMap());
    }
    private String codex(String prompt, BooleanSupplier stopped) throws Exception {
        if(executable.isBlank() || !Files.isRegularFile(Path.of(executable)))
            throw new ModelFailure("未找到Codex，请检查安装和启动配置");
        Path root=Path.of("data","internship","model-runs").toAbsolutePath();
        Files.createDirectories(root);
        Path run=Files.createTempDirectory(root,"run-"), schema=run.resolve("schema.json"), output=run.resolve("answer.json"), errors=run.resolve("stderr-private.txt");
        Process process=null;
        try {
            Files.writeString(schema,schema().toString(),StandardCharsets.UTF_8);
            List<String> command=new ArrayList<>(List.of(executable,"exec","--ignore-user-config","--ephemeral","--model",codexModel,
                    "--skip-git-repo-check","--sandbox","read-only","--color","never",
                    "-c","features.shell_tool=false","-c","features.apps=false","-c","features.multi_agent=false",
                    "-c","web_search=\"disabled\"","-c","model_reasoning_effort=\"medium\"",
                    "-C",run.toString(),"--output-schema",schema.toString(),"-o",output.toString(),"-"));
            if(fastMode)command.addAll(command.size()-1,List.of("-c","service_tier=\"priority\"","-c","features.fast_mode=true"));
            ProcessBuilder builder=new ProcessBuilder(command).directory(run.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(errors.toFile());
            // Reuse the user's enabled system proxy only for this model subprocess.
            if(!proxy.isBlank()) {
                builder.environment().put("HTTPS_PROXY",proxy);builder.environment().put("HTTP_PROXY",proxy);
                builder.environment().put("NO_PROXY","localhost,127.0.0.1");
            }
            process=builder.start();
            try(var input=process.getOutputStream()) {
                input.write(("只根据提供的文本分类。禁止使用工具、读取文件、访问网络或执行任何操作。\n"+prompt).getBytes(StandardCharsets.UTF_8));
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);
            while(!process.waitFor(250,TimeUnit.MILLISECONDS)) {
                if(stopped.getAsBoolean()) throw new InterruptedException("模型已暂停");
                if(System.nanoTime()>deadline) throw new java.util.concurrent.TimeoutException("模型响应超时");
            }
            if(stopped.getAsBoolean()) throw new InterruptedException("模型已暂停");
            if(process.exitValue()!=0 || !Files.isRegularFile(output)) throw new ModelFailure(processFailure(readErrorTail(errors),process.exitValue()));
            if(Files.size(output)>100000) throw new IllegalStateException("模型结果过大");
            return Files.readString(output,StandardCharsets.UTF_8).trim();
        } finally {
            if(process!=null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();
                process.waitFor(5,TimeUnit.SECONDS);
            }
            Files.deleteIfExists(schema); Files.deleteIfExists(output); Files.deleteIfExists(errors);
            // Only remove our empty run directory; never recursively delete arbitrary model output.
            try { Files.deleteIfExists(run); } catch(DirectoryNotEmptyException ignored) {}
        }
    }
    private static class ModelFailure extends IllegalStateException { ModelFailure(String message){super(message);} }
    static String readErrorTail(Path errors) throws java.io.IOException {
        if(!Files.isRegularFile(errors))return "";
        try(var input=Files.newInputStream(errors)) {
            input.skipNBytes(Math.max(0,Files.size(errors)-16384));
            return new String(input.readNBytes(16384),StandardCharsets.UTF_8);
        }
    }
    /** Return only controlled messages, never stderr containing prompts, credentials or local paths. */
    static String processFailure(String detail,int exit) {
        String text=detail.toLowerCase(java.util.Locale.ROOT);
        if(text.matches("(?s).*(usage limit|rate.limit|quota|too many requests|\\b429\\b).*"))return "模型额度或请求频率已达上限，请等待恢复";
        if(text.matches("(?s).*(unauthorized|not logged in|authentication|token expired|refresh.token|\\b401\\b).*"))return "模型登录失效，请重新登录Codex";
        if(text.matches("(?s).*(unsupported model|model.not.found|model[^\\n]*does not exist|invalid[^\\n]*service.tier).*"))return "模型或Fast参数不可用，请检查模型配置";
        if(text.matches("(?s).*(stream disconnected|connection|tls|dns|timed out|network|unexpected.eof|\\b50[234]\\b).*"))return "模型网络连接中断，请检查网络或代理后重试";
        return "模型程序未返回有效结果（退出码"+exit+"），未执行投递";
    }
    static JSONObject schema() {
        JSONObject properties=new JSONObject();
        properties.put("decision",new JSONObject().put("type","string").put("enum",List.of("apply","skip","review")));
        for(String k:List.of("category","reason"))properties.put(k,new JSONObject().put("type","string"));
        properties.put("confidence",new JSONObject().put("type","number"));
        properties.put("matchScore",new JSONObject().put("type","integer").put("minimum",0).put("maximum",100));
        for(String k:List.of("jdEvidence","resumeEvidence","unknowns","constraintViolations"))
            properties.put(k,new JSONObject().put("type","array").put("items",new JSONObject().put("type","string")));
        return new JSONObject().put("type","object").put("properties",properties)
                .put("required",new JSONArray(properties.keySet())).put("additionalProperties",false);
    }
}
