        la a0, $str$prototype
        lw t0, 0(a0)
        lw t1, 4(a0)
        lw t2, 8(a0)
        li t3, 1
        la a0, allChars
        li t4, 256
        mv t5, zero
initChars_1:
        sw t0, 0(a0)
        sw t1, 4(a0)
        sw t2, 8(a0)
        sw t3, 12(a0)
        sw t5, 16(a0)
        addi a0, a0, 20
        addi t5, t5, 1
        bne t4, t5, initChars_1
        jr  ra
        .data
        .align 2