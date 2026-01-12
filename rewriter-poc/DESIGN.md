# Template-Based Query Rewriter PoC 方案文档

## 1. 项目概述

### 1.1 目标
实现一个基于模板的查询改写系统，能够：
- 解析用户定义的改写规则（源模板 | 目标模板 | 约束）
- 在 RelNode 层面进行模式匹配和改写
- 支持表、属性、schema 等占位符的自动映射
- 通过约束验证确保改写的正确性

### 1.2 核心价值
- **灵活性**：用户通过声明式规则定义改写逻辑，无需编写代码
- **可靠性**：基于 Calcite 的 RelNode 层面操作，保证语义正确性
- **可扩展性**：支持 Project、Join、Filter 等多种算子的改写

## 2. 系统架构

### 2.1 整体流程

```
用户查询 (SQL)
    ↓
[1] 解析为 SqlNode
    ↓
[2] 转换为 RelNode
    ↓
[3] 加载改写规则
    ├─ 规则字符串解析 (ANTLR4)
    ├─ AST 生成
    └─ 转换为模板 SqlNode
    ↓
[4] 模板 SqlNode → 模板 RelNode
    ├─ 创建虚拟 Schema
    ├─ Validation
    └─ 转换为 RelNode
    ↓
[5] 模式匹配与改写
    ├─ RelNodeMatcher：递归匹配
    ├─ 提取占位符映射
    ├─ 验证源模板约束
    └─ 应用改写
    ↓
[6] 填充目标模板
    ├─ 根据跨模板约束
    ├─ 替换占位符
    └─ 生成改写后的 RelNode
    ↓
优化后的查询
```

### 2.2 模块划分

```
rewriter-poc/
├── src/main/java/
│   └── org.apache.shardingsphere.rewriter.poc/
│       ├── matcher/
│       │   ├── RelNodeMatcher.java          # RelNode 模式匹配器
│       │   └── SqlNodeMatcher.java           # SqlNode 模式匹配器（备用）
│       ├── rule/
│       │   └── TemplateSqlRewriteRule.java   # 自定义 RelOptRule
│       └── converter/
│           └── SqlToRelNodeConverter.java    # SqlNode ↔ RelNode 转换
└── src/test/java/
    └── TemplateRewriterPoCTest.java          # 集成测试
```

## 3. 核心组件设计

### 3.1 RelNodeMatcher（模式匹配器）

**职责**：
- 递归匹配输入 RelNode 树与模板 RelNode 树
- 识别模板中的占位符（表、属性、schema）
- 提取占位符到实际值的映射
- 验证源模板约束

**关键方法**：
```java
public MatchResult matches(
    RelNode input,              // 用户查询的 RelNode
    RelNode template,           // 源模板 RelNode
    Collection<ConstraintSegment> sourceConstraints  // 源模板约束
)
```

**匹配逻辑**：
1. **类型匹配**：检查节点类型是否一致（LogicalProject vs LogicalProject）
2. **占位符识别**：表名匹配 `t\d+` 模式的视为占位符
3. **递归匹配**：对子节点递归执行匹配
4. **约束验证**：验证 `TableEq(t0, t1)` 等源模板约束

**占位符提取**：
```java
// 表占位符：t0, t1, t2, ...
mapping.put("t0", LogicalTableScan(USERS))

// 属性占位符：a0, a1, a2, ...
mapping.put("a0", List<RexNode> [ID, NAME])

// Schema 占位符：s0, s1, ...
mapping.put("s0", RelDataType(ID: INTEGER, NAME: STRING))

// Join 条件：
mapping.put("joinCondition", RexNode: u.id = o.user_id)
```

### 3.2 TemplateSqlRewriteRule（改写规则）

**职责**：
- 实现 Calcite 的 `RelOptRule` 接口
- 在 `onMatch()` 中执行改写逻辑
- 验证跨模板约束
- 填充目标模板

**改写流程**：
```java
@Override
public void onMatch(RelOptRuleCall call) {
    RelNode input = call.rel(0);
    
    // 1. 类型预检查
    if (!input.getRelTypeName().equals(sourceTemplate.getRelTypeName())) {
        return;
    }
    
    // 2. 模式匹配
    MatchResult result = matcher.matches(input, sourceTemplate, constraints);
    if (!result.isMatched()) {
        return;
    }
    
    // 3. 填充目标模板
    Map<String, Object> mapping = result.getPlaceholderMapping();
    RelNode rewritten = instantiateTargetTemplate(targetTemplate, mapping, builder);
    
    // 4. 验证跨模板约束
    if (!validateCrossTemplateConstraints(mapping, constraints)) {
        return;
    }
    
    // 5. 应用改写
    call.transformTo(rewritten);
}
```

**目标模板填充**：
```java
private RelNode instantiateTargetTemplate(
    RelNode template,           // 目标模板
    Map<String, Object> mapping, // 占位符映射
    RelBuilder builder
) {
    // 递归处理节点
    if (isPlaceholder(template)) {
        // 查找跨模板约束：TableEq(t1, t0) → t1 使用 t0 的值
        return findValueForTargetPlaceholder(placeholderName, mapping);
    }
    
    // 处理 Project 节点
    if (template instanceof LogicalProject) {
        // 使用 AttrsEq(a1, a0) 和 SchemaEq(s1, s0) 约束
        List<RexNode> sourceProjections = findSourceProjections(mapping);
        RelDataType sourceSchema = findSourceSchema(mapping);
        return LogicalProject.create(actualInput, projections, schema);
    }
    
    // 递归处理子节点...
}
```

### 3.3 约束系统

#### 3.3.1 约束分类

**源模板约束**（在 `matches()` 中验证）：
- `TableEq(t0, t1)`：两个占位符必须映射到同一个表
- `AttrsEq(a0, a1)`：两个占位符必须映射到相同的属性
- `AttrsSub(a0, t0)`：属性 a0 必须是表 t0 的子集

**跨模板约束**（在 `onMatch()` 中用于填充）：
- `TableEq(t1_target, t0_source)`：目标占位符 t1 使用源占位符 t0 的值
- `AttrsEq(a1_target, a0_source)`：目标占位符 a1 使用源占位符 a0 的值
- `SchemaEq(s1_target, s0_source)`：目标占位符 s1 使用源占位符 s0 的值

#### 3.3.2 约束判定逻辑

```java
private boolean validateConstraint(ConstraintSegment constraint, Map<String, Object> mapping) {
    String[] params = constraint.getParams();
    
    // 检查哪些参数在映射中
    boolean param0InMapping = mapping.containsKey(params[0]);
    boolean param1InMapping = mapping.containsKey(params[1]);
    
    if (param0InMapping && param1InMapping) {
        // 两个都在源模板 → 源模板约束，验证相等性
        return validateEquality(mapping.get(params[0]), mapping.get(params[1]));
    } else if (param0InMapping || param1InMapping) {
        // 一个在源、一个在目标 → 跨模板约束，用于填充
        return true; // 在 instantiateTargetTemplate 中使用
    } else {
        // 两个都不在 → 可能都是目标模板占位符
        return true; // 跳过验证
    }
}
```

### 3.4 虚拟 Schema 机制

**问题**：模板 SQL 中的占位符（如 `t0`, `a1`）在实际 schema 中不存在，导致 validation 失败。

**解决方案**：为每个规则动态创建虚拟 schema。

#### 3.4.1 占位符提取

```java
private PlaceholderInfo extractAllPlaceholders(String ruleStr) {
    PlaceholderInfo info = new PlaceholderInfo();
    
    // 提取表占位符：Input<t0>, Input<t1>
    Pattern tablePattern = Pattern.compile("Input<(t\\d+)>");
    Matcher matcher = tablePattern.matcher(ruleStr);
    while (matcher.find()) {
        info.tables.add(matcher.group(1)); // t0, t1, ...
    }
    
    // 提取属性占位符：<a0 s0>, <a1 a2>
    Pattern attrPattern = Pattern.compile("<([a-z]\\d+)(?:\\s+([a-z]\\d+))?>");
    matcher = attrPattern.matcher(ruleStr);
    while (matcher.find()) {
        String attr = matcher.group(1);
        if (attr.startsWith("a")) {
            info.attributes.add(attr); // a0, a1, a2, ...
        }
    }
    
    return info;
}
```

#### 3.4.2 动态表生成

```java
private FrameworkConfig createTemplateSchemaConfig(String ruleStr) {
    SchemaPlus templateSchema = ...;
    PlaceholderInfo placeholders = extractAllPlaceholders(ruleStr);
    
    // 为每个表占位符创建 DynamicFlexibleTable
    for (String tableName : placeholders.tables) {
        templateSchema.add(
            tableName.toUpperCase(),
            new DynamicFlexibleTable(placeholders.attributes)
        );
    }
    
    return config;
}

class DynamicFlexibleTable extends AbstractTable {
    private final Set<String> attributePlaceholders;
    
    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        Builder builder = typeFactory.builder();
        
        // 为每个属性占位符添加列
        for (String attrName : attributePlaceholders) {
            builder.add(attrName, typeFactory.createJavaType(String.class));
        }
        
        // 添加通用列
        builder.add("id", typeFactory.createJavaType(Integer.class));
        builder.add("name", typeFactory.createJavaType(String.class));
        
        return builder.build();
    }
}
```

## 4. 规则语法

### 4.1 规则格式

```
源模板 | 目标模板 | 约束集合
```

### 4.2 支持的算子

| 算子 | 语法 | 说明 |
|------|------|------|
| Projection | `Proj<a0 s0>(...)` | 投影操作，a0=属性列表，s0=schema |
| Projection* | `Proj*<a0 s0>(...)` | 投影所有列 |
| Input | `Input<t0>` | 表输入，t0=表占位符 |
| InnerJoin | `InnerJoin<a1 a2>(L, R)` | 内连接，a1=左表列，a2=右表列 |
| LeftJoin | `LeftJoin<a1 a2>(L, R)` | 左外连接 |
| Filter | `Filter<p0>(...)` | 过滤条件，p0=谓词 |
| InSubFilter | `InSubFilter<a0>(L, R)` | 半连接 |

### 4.3 约束类型

| 约束 | 语法 | 说明 |
|------|------|------|
| TableEq | `TableEq(t0, t1)` | 表相等 |
| AttrsEq | `AttrsEq(a0, a1)` | 属性相等 |
| AttrsSub | `AttrsSub(a0, t0)` | 属性子集 |
| SchemaEq | `SchemaEq(s0, s1)` | Schema 相等 |

### 4.4 示例规则

#### 4.4.1 简单投影改写（恒等变换）

```
Proj*<a0 s0>(Input<t0>) | Proj<a1 s1>(Input<t1>) | TableEq(t1,t0);AttrsEq(a1,a0);SchemaEq(s1,s0)
```

**语义**：
- 源：从表 t0 投影所有列
- 目标：从表 t1 投影 a1 列
- 约束：
  - `TableEq(t1,t0)`：t1 使用 t0 的实际表
  - `AttrsEq(a1,a0)`：a1 使用 a0 的实际列
  - `SchemaEq(s1,s0)`：s1 使用 s0 的实际 schema

**效果**：保持查询不变（用于测试）

#### 4.4.2 Join 表交换

```
InnerJoin<a1 a2>(Input<t0>,Input<t1>) | InnerJoin<a3 a4>(Input<t2>,Input<t3>) | TableEq(t2,t1);TableEq(t3,t0);AttrsEq(a3,a2);AttrsEq(a4,a1)
```

**语义**：
- 源：t0 INNER JOIN t1 ON t0.a1 = t1.a2
- 目标：t2 INNER JOIN t3 ON t2.a3 = t3.a4
- 约束：
  - `TableEq(t2,t1)`：目标左表 = 源右表
  - `TableEq(t3,t0)`：目标右表 = 源左表
  - `AttrsEq(a3,a2)`：目标左列 = 源右列
  - `AttrsEq(a4,a1)`：目标右列 = 源左列

**效果**：交换 Join 的左右表

## 5. 技术难点与解决方案

### 5.1 模板 SQL Validation 问题

**问题**：模板 SQL 包含占位符，无法通过 Calcite 的标准 validation。

**解决方案**：
1. 从规则中提取所有占位符（表、属性）
2. 为每个规则创建独立的虚拟 schema
3. 使用 `DynamicFlexibleTable` 支持任意列名
4. 在虚拟 schema 中成功 validate 模板 SQL

### 5.2 占位符类型识别问题

**问题**：`TemplateSqlIdentifier` 被 Calcite 识别为列名而不是表名。

**解决方案**：
- 表名使用标准的 `SqlIdentifier`
- 列名可以使用 `TemplateSqlIdentifier`（如果需要特殊标记）

### 5.3 跨模板约束处理

**问题**：约束可能涉及源模板和目标模板的占位符。

**解决方案**：
- **源模板约束**：在 `matches()` 中验证，确保源查询满足条件
- **跨模板约束**：在 `onMatch()` 中使用，指导目标模板的填充

### 5.4 RelNode Schema 兼容性

**问题**：改写前后的 RelNode schema 必须兼容，否则 `call.transformTo()` 失败。

**解决方案**：
- 在 `instantiateProject()` 中使用源查询的投影表达式和 schema
- 确保目标 RelNode 的列数、列类型与源 RelNode 一致

### 5.5 Join 模板问题（待解决）

**当前问题**：
- `InnerJoin<a1 a2>(Input<t0>,Input<t1>)` 生成的是 `SqlJoin` 对象
- Calcite validator 只能处理完整的 `SqlSelect` 语句
- 需要将 `SqlJoin` 包装成 `SELECT * FROM (join)`

**临时方案**：
- 跳过 Join 测试
- 先验证 Project 改写是否正确

**长期方案**：
1. 修改 `TemplateSqlNodeConverter`，让 Join 模板生成完整的 SELECT
2. 或者扩展 Calcite 的 validation 逻辑支持 SqlJoin
3. 或者在 RelNode 层面直接构造模板，绕过 SqlNode

## 6. 测试用例

### 6.1 testSimpleProjectionRewrite

**目标**：验证简单的投影改写（恒等变换）

**输入查询**：
```sql
SELECT id, name FROM users
```

**规则**：
```
Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|TableEq(t1,t0);AttrsEq(a1,a0);SchemaEq(s1,s0)
```

**预期结果**：
- ✅ 模式匹配成功
- ✅ 提取到：t0=USERS, a0=[id,name], s0=schema
- ✅ 目标模板填充：t1=USERS, a1=[id,name], s1=schema
- ✅ 改写后查询与原查询等价

### 6.2 testJoinRewrite（待实现）

**目标**：验证 Join 表交换改写

**输入查询**：
```sql
SELECT u.name, o.amount 
FROM users u 
INNER JOIN orders o ON u.id = o.user_id
```

**规则**：交换左右表

**预期结果**：
```sql
SELECT o.amount, u.name
FROM orders o
INNER JOIN users u ON o.user_id = u.id
```

## 7. 项目结构

```
shardingsphere/
├── libs/                                    # 自定义 JAR 包目录
│   └── README.md                            # 使用说明
├── rewriter-poc/                            # PoC 项目根目录
│   ├── pom.xml                              # Maven 配置
│   ├── src/main/java/
│   │   └── org.apache.shardingsphere.rewriter.poc/
│   │       ├── matcher/
│   │       │   └── RelNodeMatcher.java      # 核心匹配逻辑
│   │       ├── rule/
│   │       │   └── TemplateSqlRewriteRule.java  # 改写规则
│   │       └── converter/
│   │           └── SqlToRelNodeConverter.java   # 转换器
│   └── src/test/java/
│       ├── TemplateRewriterPoCTest.java     # 集成测试
│       └── QuickTest.java                   # 快速测试工具
├── parser/sql/engine/dialect/rewriter/      # 规则解析器
│   └── src/main/antlr4/
│       ├── RewriterLexer.g4                 # 词法规则
│       ├── RewriterParser.g4                # 语法规则
│       └── RewriterStatement.g4             # 语句规则
└── kernel/sql-federation/compiler/          # 模板转换器
    └── src/main/java/.../template/
        ├── TemplateSqlNodeConverter.java    # AST → SqlNode
        └── TemplateSqlIdentifier.java       # 模板标识符
```

## 8. 后续工作

### 8.1 短期目标（MVP）

- [x] 完成 RelNodeMatcher 基础实现
- [x] 完成 TemplateSqlRewriteRule 框架
- [x] 实现虚拟 Schema 机制
- [x] 通过简单投影改写测试
- [ ] 解决 Join 模板 validation 问题
- [ ] 通过 Join 改写测试

### 8.2 中期目标

- [ ] 支持更多算子：Filter, Aggregate, Union
- [ ] 实现完整的约束验证逻辑
- [ ] 支持嵌套子查询改写
- [ ] 性能优化：缓存模板 RelNode

### 8.3 长期目标

- [ ] 集成到 ShardingSphere 主流程
- [ ] 支持规则优先级和冲突解决
- [ ] 提供规则管理 API
- [ ] 可视化规则编辑器

## 9. 参考资料

### 9.1 关键概念

- **RelNode**：Calcite 的关系代数节点
- **RelOptRule**：Calcite 的优化规则接口
- **SqlNode**：Calcite 的 SQL 语法树节点
- **RelNodeMatcher**：模式匹配器
- **占位符**：模板中的变量（t0, a0, s0）

### 9.2 相关文档

- Calcite RelOptRule: https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/RelOptRule.html
- ANTLR4 Grammar: https://github.com/antlr/antlr4/blob/master/doc/grammars.md
- ShardingSphere Architecture: https://shardingsphere.apache.org/document/current/cn/overview/

## 10. 附录

### 10.1 关键代码片段

#### 占位符匹配
```java
private boolean matchNode(RelNode input, RelNode template, Map<String, Object> mapping) {
    if (isPlaceholder(template)) {
        String placeholderName = extractPlaceholderName(template);
        mapping.put(placeholderName, input);
        return true;
    }
    
    if (!input.getRelTypeName().equals(template.getRelTypeName())) {
        return false;
    }
    
    // 递归匹配子节点...
}
```

#### 跨模板约束处理
```java
private Object findValueForTargetPlaceholder(String targetPlaceholder, Map<String, Object> mapping) {
    for (ConstraintSegment constraint : constraints) {
        String[] params = constraint.getParams();
        
        // TableEq(t1, t0) → t1 使用 t0 的值
        if (params[0].equals(targetPlaceholder) && mapping.containsKey(params[1])) {
            return mapping.get(params[1]);
        }
    }
    return null;
}
```

### 10.2 已知限制

1. **Join 模板**：当前无法正确处理 SqlJoin 的 validation
2. **复杂表达式**：RexNode 的占位符提取尚未完善
3. **性能**：每次改写都需要重新构建虚拟 schema
4. **错误处理**：缺少详细的错误信息和调试支持

---

**文档版本**：v1.0  
**最后更新**：2026-01-11  
**作者**：ShardingSphere Team

