# Curios Trinkets Bridge / 饰品桥

> 一个实验性的小模组，旨在让 **Curios (Forge)** 与 **Trinkets (Fabric)** 在 [Sinytra Connector](https://github.com/Sinytra/Connector) 环境下更好地协同工作。
>
> An experimental bridge mod that makes **Curios (Forge)** and **Trinkets (Fabric)** play nicely together under [Sinytra Connector](https://github.com/Sinytra/Connector).

---

## 简介 / Introduction

在使用 Sinytra Connector 同时加载 Trinkets 与 Curios 时，两个模组会各自维护**独立的饰品栏**，导致 Trinkets 的物品无法装备到 Curios 槽位。

本模组会自动把 Trinkets 物品**桥接**为 Curios 物品，保留其原本的槽位归属与生效逻辑（自定义槽位除外，因为没有对应的 Curios 槽）。

When Trinkets and Curios are loaded together via Sinytra Connector, each mod keeps its **own separate accessory inventory**, so Trinkets items cannot be equipped into Curios slots.

This mod automatically **bridges** every Trinkets item into a Curios item, preserving its original slot mapping and effects (custom Trinkets-only slots excluded, since they have no Curios counterpart).

---

## 协议 / License

本模组采用 **私有协议 (Curios Trinkets Bridge Private License)**，详见 [LICENSE](LICENSE)。

未经作者书面授权：

- ❌ 禁止任何形式的商业使用
- ❌ 禁止第三方擅自发布或转载本模组
- ✅ 任何使用本模组代码的项目必须**显著标注代码来源**
- ✅ 任何衍生作品**必须采用本协议**（具备传染性）

---

This mod is licensed under the **Curios Trinkets Bridge Private License**. See [LICENSE](LICENSE).

Without prior written permission from the author:

- ❌ No commercial use of any kind
- ❌ No unauthorized redistribution by third parties
- ✅ Any project using this mod's code must **prominently attribute the source**
- ✅ Any derivative work **must adopt this same license** (viral clause)

---

## 第三方依赖与协议 / Third-Party Dependencies & Licenses

本模组在运行时依赖以下第三方模组，**不包含其源代码**，仅通过 API 进行交互：

This mod depends on the following third-party mods at runtime. **No source code is bundled**; interaction is via APIs only:

| 模组 / Mod         | 作者 / Author      | 协议 / License                 | 链接 / Link                                |
| ------------------ | ------------------ | ------------------------------ | ------------------------------------------ |
| Curios API         | TheIllusiveC4      | LGPL-3.0 (API) / GPL-3.0 (Mod) | <https://github.com/TheIllusiveC4/Curios>  |
| Trinkets           | Emily Rose Ploszaj | MIT                            | <https://github.com/emilyploszaj/trinkets> |
| Sinytra Connector  | Su5eD              | LGPL-3.0                       | <https://github.com/Sinytra/Connector>     |

### 协议全文 / Full License Texts

- **LGPL-3.0** — <https://www.gnu.org/licenses/lgpl-3.0.html>
- **GPL-3.0**  — <https://www.gnu.org/licenses/gpl-3.0.html>
- **MIT**      — <https://opensource.org/licenses/MIT>

### 使用方式声明 / Usage Statement

- 通过 Curios LGPL 授权的 API 进行**编译时与运行时链接**，未修改其源代码。
- 通过**反射**调用 Trinkets API，未修改、未捆绑其源代码。

- Links against the **Curios API (LGPL-3.0)** at compile and runtime; no source code modified.
- Reflectively calls the **Trinkets API** at runtime; no source code modified or bundled.
