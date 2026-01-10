grammar RewriterParser;

import RewriterLexer;

// Entry rule - a rewriting rule
rule
    : template VERTICAL_BAR_ template VERTICAL_BAR_ constraintSet
    ;

// Template can be a projection, input, join, filter, or subquery filter
template
    : projectionOperator LT_ parameterList GT_ LP_ template RP_                              # ProjectionTemplate
    | INPUT LT_ IDENTIFIER GT_                                                               # InputTemplate
    | joinOperator LT_ IDENTIFIER IDENTIFIER GT_ LP_ template COMMA_ template RP_            # JoinTemplate
    | FILTER LT_ IDENTIFIER IDENTIFIER GT_ LP_ template RP_                                  # FilterTemplate
    | IN_SUB_FILTER LT_ IDENTIFIER GT_ LP_ template COMMA_ template RP_                      # InSubFilterTemplate
    ;

// Projection operators
projectionOperator
    : PROJ
    | PROJ_STAR
    ;

// Join operators
joinOperator
    : INNER_JOIN
    | LEFT_JOIN
    ;

// Parameter list (e.g., "a0 s0" or "a1 a2")
parameterList
    : IDENTIFIER+
    ;

// Constraint set - semicolon-separated constraints
constraintSet
    : constraint (SEMI_ constraint)*
    ;

// Individual constraints
constraint
    : ATTRS_SUB LP_ IDENTIFIER COMMA_ IDENTIFIER RP_                                          # AttrsSubConstraint
    | ATTRS_EQ LP_ IDENTIFIER COMMA_ IDENTIFIER RP_                                           # AttrsEqConstraint
    | TABLE_EQ LP_ IDENTIFIER COMMA_ IDENTIFIER RP_                                           # TableEqConstraint
    | SCHEMA_EQ LP_ IDENTIFIER COMMA_ IDENTIFIER RP_                                          # SchemaEqConstraint
    | PREDICATE_EQ LP_ IDENTIFIER COMMA_ IDENTIFIER RP_                                       # PredicateEqConstraint
    | UNIQUE LP_ IDENTIFIER COMMA_ IDENTIFIER RP_                                             # UniqueConstraint
    | NOT_NULL LP_ IDENTIFIER COMMA_ IDENTIFIER RP_                                           # NotNullConstraint
    | REFERENCE LP_ IDENTIFIER COMMA_ IDENTIFIER COMMA_ IDENTIFIER COMMA_ IDENTIFIER RP_      # ReferenceConstraint
    ;

