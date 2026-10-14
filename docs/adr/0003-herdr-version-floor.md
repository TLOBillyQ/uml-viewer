# 0003 Herdr version floor with pinned protocol

**Status**: accepted

Herdr 预检从精确钉死 `herdr 0.9.3` 改为版本下限 `>= 0.9.3`，同时 bundled schema 与实际 endpoint 仍必须为 protocol 22。原契约"不虚构范围"拒绝包括更高版本在内的一切其他版本，导致用户升级 Herdr 后无法启动 companion。协议才是 uml-viewer 依赖的兼容边界；版本下限只排除已知过旧的 CLI，因此不设上限，protocol 变化即拒绝。

版本串必须是纯数字 `X.Y.Z`；预发布、build 后缀或 `v` 前缀均拒绝，避免对非正式版本做顺序推断。

唯一有原生验收证据的仍是 0.9.3。高于 0.9.3 的版本按协议门槛放行，但不宣称已验收；历史验收文档保持原样。
