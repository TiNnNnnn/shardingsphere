#!/bin/bash

# Template-based Query Rewriter PoC Quick Start

echo "==================================================="
echo "  Template-based Query Rewriter PoC"
echo "==================================================="
echo ""

cd "$(dirname "$0")"

echo "Step 1: Clean and compile the project..."
mvn clean compile

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed!"
    exit 1
fi

echo ""
echo "✅ Compilation successful!"
echo ""

echo "Step 2: Run tests..."
mvn test

if [ $? -ne 0 ]; then
    echo "❌ Tests failed!"
    exit 1
fi

echo ""
echo "✅ All tests passed!"
echo ""

echo "==================================================="
echo "  PoC Completed Successfully!"
echo "==================================================="
echo ""
echo "Next steps:"
echo "  1. Review the test output above"
echo "  2. Check README.md for more details"
echo "  3. Extend the rules in test cases"
echo ""

