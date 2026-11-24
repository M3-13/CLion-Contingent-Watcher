package io.github.m313.contingentwatcher.core

/**
 * Best practices for using GitHub Copilot sparingly (researched November 2026).
 * Deliberately no prices or multipliers: they change; the model picker shows them.
 * `when` decides in which situations a tip is shown (see Tips.kt).
 */
val TIPS: List<Tip> = listOf(
    Tip("newChatPerTask", listOf("general", "ahead", "critical", "onTrack"), "Start a new chat whenever the topic changes. A clean context is cheaper per request and gives better answers."),
    Tip("compactLongChats", listOf("ahead", "critical"), "In long chats run /compact to summarise older turns instead of dragging the whole history into every request."),
    Tip("lighterModelSimple", listOf("general", "ahead", "critical"), "Use a lighter model for quick edits, boilerplate and direct questions; keep reasoning models for hard problems."),
    Tip("lowerThinkingEffort", listOf("ahead", "critical"), "If your model offers a lower thinking/reasoning level in the model picker, use it for routine tasks; deep reasoning burns many output tokens."),
    Tip("trimToolList", listOf("general", "ahead", "critical"), "Enable only the tools a task needs via Configure Tools. Every tool definition and result adds to the context you pay for."),
    Tip("limitMcpServers", listOf("ahead", "critical", "lateMonth"), "Disable MCP servers you are not using in this session; large tool catalogs inflate input on every request."),
    Tip("keepCacheWarm", listOf("onTrack", "ahead"), "Stay in one focused session for one task. Switching models or settings mid-session can make the next requests more expensive."),
    Tip("autoModel", listOf("general", "earlyMonth", "ahead"), "Try the Auto model option; it routes by task so simple requests go to cheaper models. Check the model picker for details."),
    Tip("includedModels", listOf("critical", "exhausted", "earlyMonth"), "Prefer models marked as included or lowest cost in the model picker for everyday work, and go premium only when needed."),
    Tip("freeCompletions", listOf("exhausted", "critical", "general"), "Inline completions and next edit suggestions do not use your allowance. Lean on them for small edits instead of chat."),
    Tip("exhaustedPlan", listOf("exhausted"), "With the allowance used up, keep working with completions and next edit suggestions, and plan chat tasks for the next reset."),
    Tip("exhaustedPrepare", listOf("exhausted"), "While blocked, write prompts, specs and instruction files by hand so the next session starts with precise, cheap requests."),
    Tip("exhaustedDebugLocal", listOf("exhausted"), "Use the debugger, tests and search yourself before asking chat; reproduce the bug so a later prompt can be one short request."),
    Tip("exhaustedBudget", listOf("exhausted", "critical"), "If you truly need more, an additional spending budget can be set in your GitHub billing settings. Set a hard cap you can live with."),
    Tip("spendingLimit", listOf("general", "earlyMonth"), "Set a spending limit or budget in GitHub billing so overages cannot surprise you, and check your usage page regularly."),
    Tip("planFirst", listOf("general", "earlyMonth", "onTrack"), "Use Plan mode or a planning custom agent first, review the plan, then implement it in a fresh chat with the plan attached."),
    Tip("askForQuestions", listOf("ahead", "critical", "general"), "Use Ask mode for explanations and questions; it avoids agent loops with many tool calls and costs less than agent mode."),
    Tip("agentForMultiStep", listOf("ahead", "critical"), "Reserve agent mode for multi-file, multi-step work. For a one-file change, a precise prompt in inline chat is enough."),
    Tip("specificContext", listOf("general", "ahead", "critical"), "Attach exact files with #file or #selection instead of #codebase when you already know where the change belongs."),
    Tip("avoidBigAttachments", listOf("ahead", "critical"), "Do not attach whole logs, huge files or many images. Paste only the relevant lines, since long input costs more on every turn."),
    Tip("oneGoalPerPrompt", listOf("general", "onTrack"), "Give one clear goal per prompt with acceptance criteria and a named example. Vague prompts cause costly retry rounds."),
    Tip("copilotInstructions", listOf("earlyMonth", "general"), "Put project rules, build and test commands in .github/copilot-instructions.md so you stop repeating them in every prompt."),
    Tip("scopedInstructions", listOf("earlyMonth", "general"), "Use *.instructions.md files with an applyTo glob so language- or folder-specific rules load only when relevant."),
    Tip("promptFiles", listOf("earlyMonth", "onTrack"), "Turn repeated tasks into .prompt.md files and run them with a slash command for consistent, short requests."),
    Tip("customAgentModel", listOf("earlyMonth", "general"), "Define custom agents with a restricted tools list and a fixed model, e.g. a cheap one for docs and a strong one for design."),
    Tip("reviewDiffs", listOf("general", "onTrack"), "Review each diff before accepting. Catching a wrong turn early is cheaper than a long chain of correction prompts."),
    Tip("checkpointsUndo", listOf("ahead", "critical", "general"), "Use checkpoints and undo to roll back a bad agent run instead of asking the model to repair its own mess."),
    Tip("testsFirst", listOf("onTrack", "general"), "Ask for tests first, then the implementation, and let the agent run them. A failing test is a precise, short feedback loop."),
    Tip("codeReviewCost", listOf("ahead", "critical"), "Copilot code review on private repos also uses Actions minutes. Trigger it on PRs that matter, not on every small push."),
    Tip("localReviewSurplus", listOf("surplus"), "Use leftover allowance for a deep review of your riskiest module with a strong model, or a Copilot review before merging a big PR."),
    Tip("surplusRefactor", listOf("surplus", "lateMonth"), "Before the month ends, spend spare allowance on a planned refactor or migration with a premium model, in small reviewed steps."),
    Tip("surplusTestsDocs", listOf("surplus", "lateMonth"), "Let a premium model write missing tests, docs and README sections now; leftover allowance expires at the reset."),
    Tip("surplusInstructions", listOf("surplus", "lateMonth"), "Spend spare credits letting an agent draft your copilot-instructions.md and prompt files, then trim them by hand."),
    Tip("cloudAgentBatch", listOf("surplus", "general"), "The cloud coding agent works asynchronously on issues and its usage is billed too. Hand it well-scoped issues, not vague ones."),
    Tip("sideQuestions", listOf("general", "ahead"), "Ask quick side questions in the btw window (Ctrl+Alt+Shift+B) so they don't bloat the context of your main task."),
    Tip("planMonthStart", listOf("earlyMonth"), "At the start of the month, split the allowance into weekly shares and note which big tasks deserve premium models."),
    Tip("earlyMonthSetup", listOf("earlyMonth"), "Early in the month invest in setup: instructions, prompt files and tool selection pay back across every later request."),
    Tip("lateMonthBatch", listOf("lateMonth", "critical"), "Late in the month, batch small questions into one precise prompt and defer large explorations to after the reset."),
    Tip("keepHabits", listOf("onTrack", "lateMonth"), "You are on track: keep fresh chats, scoped context and cheaper models for routine work, and check usage weekly."),
    Tip("dailyBudget", listOf("onTrack", "ahead", "earlyMonth"), "Check the daily budget in the status bar: it tells you what you can spend today and still get through to the reset."),
)
