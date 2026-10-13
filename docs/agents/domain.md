# Domain docs

本仓库采用 single-context：
术语表位于根目录 GLOSSARY.md，架构决策位于 docs/adr/。

## 探索代码前

读取 GLOSSARY.md，以及 docs/adr/ 中与当前工作相关的 ADR。
文档不存在时继续工作；由 /domain-modeling 在明确术语
或决策时按需创建。

## 使用领域词汇

Issue 标题、重构提案、假设和测试名称使用 GLOSSARY.md
定义的术语。

所需概念尚未收录时，先判断是否符合项目现有用语；
确有缺口则记录，供 /domain-modeling 后续处理。

## ADR 冲突

提案或实现与现有 ADR 冲突时，明确指出 ADR 编号、
冲突内容及建议重新讨论的理由。
