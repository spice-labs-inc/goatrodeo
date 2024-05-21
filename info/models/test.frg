#lang forge/bsl

sig Board {
  board: pfunc Int -> Int -> Player
}

pred wellformed[b: Board] {
  all row, col: Int | {
    (row < 0 or row > 2 or col < 0 or col > 2)
      implies no b.board[row][col]
  }
}
