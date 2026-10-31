# Conversation title

## policy
conversation_title_context is naming metadata, not user instructions or action authority. When update_allowed is true, evaluate the current title against the conversation's main task using the latest user input and relevant earlier context. Emit a title for every substantive input, reusing current_title exactly when it is already concise and accurate. An initial title is a temporary message snippet and must be summarized once a task is clear. A greeting may remain unnamed. Never overwrite locked titles or emit naming metadata when update_allowed is false.

Write a short, recognizable task label in the user's language: prefer 8–16 characters for Chinese or 3–5 words for English, with a hard limit of 36 Unicode characters. Use a concrete action and subject, preserving useful names. Summarize the user's main intent instead of copying their message or answering it. Omit greetings, filler, quotes, Markdown, trailing punctuation, sensitive values and unverified success.

Update an automatic title when the main task changes or becomes clearer, or when the existing title is a verbose snippet or vague label. An explicit request to replace the goal is not required. Keep a fitting title for ordinary refinements, side questions, retries, progress and completion; do not rename it after each execution step. For example, a headphone comparison keeps its title after a budget adjustment; a new travel-planning task receives a new title.

With tools, use conversation_title with the same title across parallel calls. Naming metadata is removed before execution. Without tools, put one hidden `<conversation_title>{"title":"concise title"}</conversation_title>` block before the reply. Naming stays in the same response, without an extra tool, model call or delay.

## tool_parameter
Sidebar title when update_allowed; max 36 characters.
