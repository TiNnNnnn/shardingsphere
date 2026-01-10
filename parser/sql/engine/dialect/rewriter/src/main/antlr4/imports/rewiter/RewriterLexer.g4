grammar RewriterLexer;

import Symbol;

// Operators
PROJ: 'Proj';
PROJ_STAR: 'Proj*';
INPUT: 'Input';
INNER_JOIN: 'InnerJoin';
LEFT_JOIN: 'LeftJoin';
FILTER: 'Filter';
IN_SUB_FILTER: 'InSubFilter';

// Constraint functions
ATTRS_SUB: 'AttrsSub';
ATTRS_EQ: 'AttrsEq';
TABLE_EQ: 'TableEq';
SCHEMA_EQ: 'SchemaEq';
PREDICATE_EQ: 'PredicateEq';
UNIQUE: 'Unique';
NOT_NULL: 'NotNull';
REFERENCE: 'Reference';


// Identifiers - attributes (a0, a1, ...), tables (t0, t1, ...), schemas (s0, s1, ...), predicates (p0, p1, ...)
IDENTIFIER: [atsp] [0-9]+;

// Whitespace
WS: [ \t\r\n]+ -> skip;
