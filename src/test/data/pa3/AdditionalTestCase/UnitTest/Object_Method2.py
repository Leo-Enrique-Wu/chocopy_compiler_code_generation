class A(object):
    def foo(self:"A",x:int,y:int)->int:
        return x+y
    def bar(self:"A",x:int,y:int)->int:
        return x-y
a:A=None
x:int = 3
a = A()
print(a.foo(x,a.bar(5,1)))