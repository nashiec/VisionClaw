package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ToolCallRouter(
    private val bridge: OpenClawBridge,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ToolCallRouter"
    }

    private val inFlightJobs = mutableMapOf<String, Job>()

    fun handleToolCall(
        call: GeminiFunctionCall,
        sendResponse: (JSONObject) -> Unit,
        onComplete: (() -> Unit)? = null
    ) {
        val callId = call.id
        val callName = call.name

        Log.d(TAG, "Received: $callName (id: $callId) args: ${call.args}")

        val job = scope.launch {
            val taskDesc = call.args["task"]?.toString() ?: call.args.toString()
            val result = bridge.delegateTask(task = taskDesc, toolName = callName)

            // Always send the response even if Gemini cancelled the tool call,
            // because the work was already completed on the server side.
            val truncatedResult = when (result) {
                is ToolResult.Success -> ToolResult.Success(
                    result.result.take(2000).let {
                        if (result.result.length > 2000) "$it... [truncated]" else it
                    }
                )
                is ToolResult.Failure -> result
            }
            Log.d(TAG, "Result for $callName (id: $callId): $truncatedResult")
            val response = buildToolResponse(callId, callName, truncatedResult)
            sendResponse(response)
            onComplete?.invoke()

            inFlightJobs.remove(callId)
        }

        inFlightJobs[callId] = job
    }

    fun cancelToolCalls(ids: List<String>) {
        for (id in ids) {
            inFlightJobs[id]?.let { job ->
                Log.d(TAG, "Cancelling in-flight call: $id")
                job.cancel()
                inFlightJobs.remove(id)
            }
        }
        bridge.setToolCallStatus(ToolCallStatus.Cancelled(ids.firstOrNull() ?: "unknown"))
    }

    fun cancelAll() {
        for ((id, job) in inFlightJobs) {
            Log.d(TAG, "Cancelling in-flight call: $id")
            job.cancel()
        }
        inFlightJobs.clear()
    }

    private fun buildToolResponse(
        callId: String,
        name: String,
        result: ToolResult
    ): JSONObject {
        return JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().put(JSONObject().apply {
                    put("id", callId)
                    put("name", name)
                    put("response", result.toJSON())
                }))
                put("scheduling", "WHEN_IDLE")
            })
        }
    }
}
