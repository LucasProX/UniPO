/**
 * ESLint 基线（2026-10-20 加）。
 *
 * 只开一小撮「一定错」的规则，不引官方 recommended：
 * 项目现在的代码风格是手写的，一次上全量 recommended 会冒出几百条纯风格告警，
 * 那种 PR 除了噪音什么都得不到。这里只挡真的会让运行行为出问题的写法，
 * 以后想加规则再一条条加，加之前先让代码是绿的。
 *
 * 类型层面的事交给 `vue-tsc`（npm run build）和 `src/api-contract.ts`，
 * 所以不需要 @typescript-eslint recommended。
 */
import vueParser from 'vue-eslint-parser';
import tsParser from '@typescript-eslint/parser';

export default [
  { ignores: ['dist/**', 'node_modules/**', 'src/api-types.ts'] },
  {
    files: ['src/**/*.ts', 'src/**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: { parser: tsParser, ecmaVersion: 2022, sourceType: 'module' },
    },
    rules: {
      // 调试残留：debugger 一定不能进产物
      'no-debugger': 'error',
      // var 提升 + 作用域问题，TS 项目里没有理由再写
      'no-var': 'error',
      'prefer-const': 'error',
      // == null 是刻意写法，其余宽松比较一律拦
      eqeqeq: ['error', 'smart'],
      'no-duplicate-case': 'error',
      'no-unreachable': 'error',
      'no-unsafe-finally': 'error',
      // 循环里的 await（并发写法容易写错，提示但不挡）
      'no-await-in-loop': 'warn',
    },
  },
];
