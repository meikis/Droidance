# Model provider presets

On first installation, Droidance creates disabled provider entries from `app/src/main/assets/model_capabilities/provider_presets.json`. Only provider names, API addresses, and protocols are prefilled. **No model IDs or credentials are preset.** Enter your API key and an exact model ID from the provider console, then save to enable an entry. Select a model that supports image input and tool calling for mobile tasks.

Existing saved lists are preserved, including an intentionally empty list. Deleted entries stay deleted. To add another preset later, tap **+** and select a provider, or select **Custom** for a blank configuration. Changing the template clears the API key and model fields. Unconfigured entries open the editor when their list switch is tapped.

| Provider | API base URL | Official reference |
| --- | --- | --- |
| OpenRouter | `https://openrouter.ai/api/v1` | [API documentation](https://openrouter.ai/developers) |
| OpenAI | `https://api.openai.com/v1` | [API documentation](https://developers.openai.com/api/reference/resources/responses/methods/create) |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | [API documentation](https://ai.google.dev/gemini-api/docs/openai) |
| DeepSeek | `https://api.deepseek.com` | [API documentation](https://api-docs.deepseek.com/quick_start/agent_integrations/) |
| Alibaba Cloud | `https://dashscope.aliyuncs.com/compatible-mode/v1` | [API documentation](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions) |
| SiliconFlow | `https://api.siliconflow.cn/v1` | [API documentation](https://docs.siliconflow.cn/docs/userguide/quickstart) |
| Kimi | `https://api.moonshot.ai/v1` | [API documentation](https://platform.kimi.ai/docs/get-your-api-key) |
| Zhipu AI | `https://open.bigmodel.cn/api/paas/v4` | [API documentation](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction) |
| MiniMax | `https://api.minimax.io/v1` | [API documentation](https://platform.minimax.io/docs/api-reference/text-openai-api) |
| xAI | `https://api.x.ai/v1` | [API documentation](https://docs.x.ai/developers/quickstart) |
| Groq | `https://api.groq.com/openai/v1` | [API documentation](https://console.groq.com/docs/vision) |
| Mistral AI | `https://api.mistral.ai/v1` | [API documentation](https://docs.mistral.ai/api) |
| Volcengine | `https://ark.cn-beijing.volces.com/api/v3` | [API documentation](https://docs.volcengine.com/docs/ark/quick-start?lang=zh) |
| Tencent Hunyuan | `https://api.hunyuan.cloud.tencent.com/v1` | [API documentation](https://cloud.tencent.com/document/product/1729/111007) |
| Baidu Qianfan | `https://qianfan.baidubce.com/v2` | [API documentation](https://cloud.baidu.com/doc/qianfan-api/s/Dmba8k71y) |
| StepFun | `https://api.stepfun.com/v1` | [API documentation](https://platform.stepfun.ai/cookbook/step-5-tool-calling) |
| NVIDIA NIM | `https://integrate.api.nvidia.com/v1` | [API documentation](https://docs.api.nvidia.com/nim/reference/llm-apis) |
| Together AI | `https://api.together.ai/v1` | [API documentation](https://docs.together.ai/docs/inference/openai-compatibility) |
| Fireworks AI | `https://api.fireworks.ai/inference/v1` | [API documentation](https://docs.fireworks.ai/tools-sdks/openai-compatibility) |
| Hugging Face | `https://router.huggingface.co/v1` | [API documentation](https://huggingface.co/docs/inference-providers/tasks/chat-completion) |
| ModelScope | `https://api-inference.modelscope.cn/v1` | [API documentation](https://modelscope.cn/docs/model-service/API-Inference/intro) |
| Novita AI | `https://api.novita.ai/openai/v1` | [API documentation](https://docs.novita.ai/api-reference/model-apis-llm-create-chat-completion) |

OpenAI and xAI use the existing Responses transport; other presets use Chat Completions. Endpoint information was checked against official documentation on 2026-11-01. Presets are editable starting points, not proof of account access. Model availability, image limits, billing, and regional access depend on the provider and account.

Alibaba Cloud, SiliconFlow, Volcengine, Tencent Hunyuan, Baidu Qianfan, StepFun, and ModelScope use mainland China endpoints. Alibaba Cloud documents that its existing DashScope hostname remains usable; accounts requiring a workspace-specific hostname should copy it from their console. Kimi and MiniMax use international endpoints. Use the matching regional endpoint and key when applicable. Hugging Face uses an access token with inference permissions in the API key field.

Direct Anthropic Messages, AWS Bedrock authentication, and deployment-specific Azure endpoints are not supported by these fixed-endpoint presets. Claude models can be used through a compatible gateway such as OpenRouter by entering the model ID available to your account.
