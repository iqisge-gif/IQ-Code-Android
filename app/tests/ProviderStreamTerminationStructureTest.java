import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ProviderStreamTerminationStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String read(Path root,String file)throws Exception{return new String(Files.readAllBytes(root.resolve(file)),StandardCharsets.UTF_8);}
    private static void requireOrdered(String source,String first,String second,String message){
        require(source.indexOf(first)>=0&&source.indexOf(second)>source.indexOf(first),message);
    }
    public static void main(String[]args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String anthropic=read(root,"src/com/termux/app/iqcode/api/AnthropicMessagesProvider.java");
        String chat=read(root,"src/com/termux/app/iqcode/api/OpenAIChatCompletionsProvider.java");
        String responses=read(root,"src/com/termux/app/iqcode/api/OpenAIResponsesProvider.java");
        String tracker=read(root,"src/com/termux/app/iqcode/api/HttpRequestTracker.java");
        String modelProvider=read(root,"src/com/termux/app/iqcode/api/ModelProvider.java");
        String engine=read(root,"src/com/termux/app/iqcode/core/IQCodeEngine.java");
        require(anthropic.contains("message_stop")&&anthropic.contains("terminalEventSeen")&&anthropic.contains("stream_read_error"),"Anthropic stream must require message_stop and report incomplete EOF");
        require(anthropic.contains("terminalEventSeen = true;\n                    break;"),"Anthropic must stop reading as soon as message_stop arrives");
        requireOrdered(anthropic,"while ((line = reader.readLine()) != null)","if (!terminalEventSeen)","Anthropic EOF must be checked after stream reading");
        require(anthropic.contains("catch (IOException e)")&&anthropic.contains("Thread.currentThread().isInterrupted()"),"Anthropic transport errors must preserve cancellation detection");
        require(chat.contains("terminalEventSeen")&&chat.contains("[DONE]")&&chat.contains("before [DONE]"),"Chat stream must require [DONE]");
        require(chat.contains("state.terminalEventSeen = true;\n                    break;"),"Chat must stop reading as soon as [DONE] arrives");
        requireOrdered(chat,"readSse(conn.getInputStream()","if (!state.terminalEventSeen)","Chat EOF must be checked after SSE reading");
        require(chat.contains("JSONObject error = chunk.optJSONObject(\"error\")"),"Chat stream errors must not be silently ignored");
        require(chat.contains("if (!streaming)")&&chat.contains("parseNonStreaming"),"Chat JSON responses must keep a separate non-streaming path");
        require(responses.contains("terminalEventSeen")&&responses.contains("response.completed")&&responses.contains("response.incomplete"),"Responses stream must require a terminal response event");
        require(responses.contains("if (state.terminalEventSeen) break;"),"Responses must stop reading after its terminal event");
        requireOrdered(responses,"readSse(conn.getInputStream()","if (!state.terminalEventSeen)","Responses EOF must be checked after SSE reading");
        require(responses.contains("before response.completed/response.incomplete")&&responses.contains("optString(\"code\""),"Responses incomplete EOF and upstream error codes must be preserved");
        require(modelProvider.contains("class StreamFailure")&&modelProvider.contains("public final String code"),"stream failures must preserve a machine-readable error code");
        require(engine.contains("requestModelWithRetry")&&engine.contains("transportRetryAttempted")&&engine.contains("onResponseRetry()")&&engine.contains("canReplayModelRequest"),"model transport failures must have a bounded retry before assistant/tool execution");
        require(engine.contains("request_timeout")&&engine.contains("stream_read_error")&&engine.contains("codex-responses")&&engine.contains("native"),"retry policy must classify transient failures and protect native server-side tools");
        require(engine.contains("isInterruptedFailure")&&engine.contains("cancelRequest(worker)"),"主动取消必须继续走现有中断和连接取消路径");
        require(tracker.contains("READ_IDLE_TIMEOUT_MS = 300_000")&&tracker.contains("setReadTimeout(READ_IDLE_TIMEOUT_MS)"),"provider reads must have one finite shared idle timeout");
        require(tracker.contains("request_timeout")&&tracker.contains("connection_reset")&&tracker.contains("stream_read_error"),"transport failures must be classified by request stage");
        String[] providers={anthropic,chat,responses};
        for(String provider:providers){
            require(provider.contains("new HttpRequestTracker()")&&provider.contains("requests.cancel(worker)"),"every provider must share tracked cancellation");
            requireOrdered(provider,"requests.begin(conn)","conn.getOutputStream()","connection registration must precede request I/O");
            requireOrdered(provider,"conn.getResponseCode()","request.markResponseStarted()","response stage must be marked after status arrives");
            require(provider.contains("request.close()")&&!provider.contains("activeConnections")&&!provider.contains("setReadTimeout(0)"),"every provider must use one tracked cleanup path and finite timeout");
        }

        System.out.println("ProviderStreamTerminationStructureTest PASS");
    }
}
