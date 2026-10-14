---
name: lighthouse-enhance
description: Enhance one coding request with Lighthouse security guidance, then implement it. Use only when the developer explicitly invokes it.
---

# Lighthouse enhance

Run only because the developer invoked this skill. Their coding request is the text after the
invocation.

1. If the request starts with `--branch <name>`, take `<name>` as the branch and drop that flag
   from the request. Change nothing else in the request.
2. Call `lighthouse_enhance_prompt` once with `prompt` set to the request exactly as written (no rewriting,
   summarizing, translating or reformatting). Set `branch` only when step 1 gave one, and never
   put the branch in `prompt`.
3. Implement the original request, following the returned guidance in addition to it.
4. Tell the developer the branch and any warnings the tool reported.
5. If the tool fails (not configured, out of credits, API error), explain the failure and stop.
   Do not implement until the developer asks to retry or explicitly says to proceed without
   enhancement.

Set up by `reware-lighthouse init`; credentials stay in `reware-lighthouse login`, not in this file.
