# 系统架构图

## 1. 整体流程图

```mermaid
graph TD
    A[用户查询 SQL] --> B[SqlParser]
    B --> C[SqlNode]
    C --> D[SqlToRelConverter]
    D --> E[原始 RelNode]
    
    F[规则字符串] --> G[ANTLR4 Parser]
    G --> H[AST]
    H --> I[TemplateSqlNodeConverter]
    I --> J[模板 SqlNode]
    
    K[虚拟 Schema] --> L[SqlValidator]
    J --> L
    L --> M[模板 RelNode]
    
    E --> N[RelNodeMatcher]
    M --> N
    N --> O{匹配成功?}
    
    O -->|是| P[提取占位符映射]
    P --> Q[验证源模板约束]
    Q --> R{约束通过?}
    
    R -->|是| S[填充目标模板]
    S --> T[生成改写后的 RelNode]
    T --> U[应用改写]
    
    O -->|否| V[跳过改写]
    R -->|否| V
```

## 2. 组件关系图

```mermaid
classDiagram
    class TemplateSqlRewriteRule {
        -RelNode sourceTemplate
        -RelNode targetTemplate
        -Collection~ConstraintSegment~ constraints
        -RelNodeMatcher matcher
        +onMatch(RelOptRuleCall)
        -instantiateTargetTemplate()
        -validateCrossTemplateConstraints()
    }
    
    class RelNodeMatcher {
        +matches(RelNode, RelNode, Constraints) MatchResult
        -matchNodeRecursive()
        -matchProject()
        -matchJoin()
        -extractProjectionPlaceholders()
        -validateSourceConstraints()
    }
    
    class MatchResult {
        -boolean matched
        -Map~String,Object~ placeholderMapping
        +isMatched()
        +getPlaceholderMapping()
    }
    
    class SqlToRelNodeConverter {
        -CalciteCatalogReader catalogReader
        -SqlValidator validator
        -RelOptCluster cluster
        +convertToRelNode(SqlNode) RelNode
    }
    
    class TemplateSqlNodeConverter {
        +convert(RewriteRuleSegment) TemplateRewriteRule
        -convertTemplate(ASTNode) SqlNode
        -convertJoinTemplate()
        -convertProjectionTemplate()
    }
    
    class DynamicFlexibleTable {
        -Set~String~ attributePlaceholders
        +getRowType() RelDataType
    }
    
    TemplateSqlRewriteRule --> RelNodeMatcher
    RelNodeMatcher --> MatchResult
    TemplateSqlRewriteRule --> SqlToRelNodeConverter
    TemplateSqlNodeConverter --> DynamicFlexibleTable
```

## 3. 匹配流程序列图

```mermaid
sequenceDiagram
    participant User as 用户查询
    participant Rule as TemplateSqlRewriteRule
    participant Matcher as RelNodeMatcher
    participant Input as 输入 RelNode
    participant Template as 模板 RelNode
    
    User->>Rule: onMatch(RelOptRuleCall)
    Rule->>Rule: 类型预检查
    
    Rule->>Matcher: matches(input, template, constraints)
    Matcher->>Input: 获取节点类型
    Matcher->>Template: 获取节点类型
    
    alt 节点类型匹配
        Matcher->>Template: isPlaceholder()?
        alt 是占位符
            Matcher->>Matcher: 提取占位符名称
            Matcher->>Matcher: 记录映射: t0 → USERS
        else 非占位符
            Matcher->>Matcher: 递归匹配子节点
        end
        
        Matcher->>Matcher: 验证源模板约束
        Matcher-->>Rule: MatchResult(matched=true, mapping)
        
        Rule->>Rule: instantiateTargetTemplate()
        Rule->>Rule: 查找跨模板约束 TableEq(t1,t0)
        Rule->>Rule: 替换: t1 → USERS
        Rule->>Rule: 构建新的 RelNode
        
        Rule->>Rule: validateCrossTemplateConstraints()
        Rule->>User: call.transformTo(rewrittenRel)
    else 节点类型不匹配
        Matcher-->>Rule: MatchResult(matched=false)
        Rule->>User: return (跳过改写)
    end
```

## 4. 占位符提取流程

```mermaid
graph LR
    A[规则字符串] --> B{解析占位符}
    
    B --> C[提取表占位符]
    C --> C1[Input&lt;t0&gt;]
    C --> C2[Input&lt;t1&gt;]
    C1 --> D[tables: Set]
    C2 --> D
    
    B --> E[提取属性占位符]
    E --> E1[&lt;a0 s0&gt;]
    E --> E2[&lt;a1 a2&gt;]
    E1 --> F[attributes: Set]
    E2 --> F
    
    D --> G[创建虚拟 Schema]
    F --> G
    
    G --> H[DynamicFlexibleTable]
    H --> I[添加占位符列: a0, a1, a2]
    H --> J[添加通用列: id, name]
    
    I --> K[完整的虚拟表]
    J --> K
```

## 5. 约束验证流程

```mermaid
graph TD
    A[约束集合] --> B{检查约束类型}
    
    B --> C[TableEq]
    B --> D[AttrsEq]
    B --> E[SchemaEq]
    B --> F[AttrsSub]
    
    C --> G{检查参数位置}
    D --> G
    E --> G
    F --> G
    
    G -->|两个参数都在源模板| H[源模板约束]
    G -->|一个源, 一个目标| I[跨模板约束]
    G -->|两个参数都在目标模板| J[目标模板约束]
    
    H --> K[在 matches 中验证]
    K --> L{验证相等性}
    L -->|通过| M[继续]
    L -->|失败| N[匹配失败]
    
    I --> O[在 onMatch 中使用]
    O --> P[填充目标模板]
    P --> Q[提取源值]
    Q --> R[替换目标占位符]
    
    J --> S[暂不处理]
```

## 6. 目标模板填充流程

```mermaid
graph TD
    A[目标模板 RelNode] --> B{检查节点类型}
    
    B -->|占位符节点| C[提取占位符名称: t1]
    C --> D[查找跨模板约束]
    D --> E[找到: TableEq t1,t0]
    E --> F[从映射获取: t0 → USERS]
    F --> G[替换: t1 → USERS]
    
    B -->|Project 节点| H[查找 AttrsEq a1,a0]
    H --> I[从映射获取: a0 → ID,NAME]
    I --> J[查找 SchemaEq s1,s0]
    J --> K[从映射获取: s0 → Schema]
    K --> L[创建新 Project]
    L --> M[使用源的投影和 Schema]
    
    B -->|Join 节点| N[查找 joinCondition]
    N --> O[从映射获取条件]
    O --> P[创建新 Join]
    P --> Q[使用源的 join 条件]
    
    B -->|其他节点| R[递归处理子节点]
    
    G --> S[返回填充后的 RelNode]
    M --> S
    Q --> S
    R --> S
```

## 7. 测试流程图

```mermaid
graph TD
    A[测试开始] --> B[解析用户查询]
    B --> C[转换为 RelNode]
    
    D[解析规则字符串] --> E[生成 AST]
    E --> F[转换为模板 SqlNode]
    
    G[提取占位符] --> H[创建虚拟 Schema]
    H --> I[验证模板 SqlNode]
    I --> J[转换为模板 RelNode]
    
    C --> K[创建 TemplateSqlRewriteRule]
    J --> K
    
    K --> L[创建 HepPlanner]
    L --> M[添加改写规则]
    M --> N[执行优化]
    
    N --> O{改写成功?}
    O -->|是| P[验证改写后的 RelNode]
    O -->|否| Q[测试失败]
    
    P --> R{Schema 匹配?}
    R -->|是| S[测试通过]
    R -->|否| Q
```

## 8. 数据流图

```mermaid
graph LR
    subgraph 输入
        A1[用户 SQL] 
        A2[规则字符串]
    end
    
    subgraph 解析层
        B1[SqlParser]
        B2[ANTLR4 Parser]
    end
    
    subgraph AST 层
        C1[SqlNode]
        C2[RewriteRuleSegment]
    end
    
    subgraph 转换层
        D1[SqlToRelConverter]
        D2[TemplateSqlNodeConverter]
        D3[虚拟 Schema]
    end
    
    subgraph RelNode 层
        E1[原始 RelNode]
        E2[模板 RelNode]
    end
    
    subgraph 匹配层
        F1[RelNodeMatcher]
        F2[占位符映射]
    end
    
    subgraph 改写层
        G1[TemplateSqlRewriteRule]
        G2[改写后 RelNode]
    end
    
    A1 --> B1 --> C1 --> D1 --> E1
    A2 --> B2 --> C2 --> D2
    D2 --> D3 --> E2
    
    E1 --> F1
    E2 --> F1
    F1 --> F2
    
    F2 --> G1
    E2 --> G1
    G1 --> G2
```

---

这些图表可以在支持 Mermaid 的 Markdown 查看器中查看，如：
- GitHub
- GitLab
- Typora
- VS Code (with Mermaid extension)

